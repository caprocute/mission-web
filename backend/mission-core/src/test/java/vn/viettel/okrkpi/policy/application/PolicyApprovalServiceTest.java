package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approved;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Approver;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService.Submit;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovalNotices;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovers;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishLauncher;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository.NumberedRun;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyTestRun;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.TestRunResult;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-05-09: gửi, ký phê duyệt và từ chối phát hành; băm nội dung ổn định. */
class PolicyApprovalServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
  private static final String HASH =
      "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0";

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID approverId = UUID.randomUUID();
  private final UUID runId = UUID.randomUUID();
  private final UUID periodId = UUID.randomUUID();
  private final PolicyPack pack =
      new PolicyPack(
          UUID.randomUUID(),
          "khoi-dang",
          "Bộ quy định khối Đảng",
          "DANG",
          "DANG",
          PackOrigin.GOC_TOAN_CUC,
          null,
          "QĐ 366",
          NOW,
          author,
          0);

  private PolicyPackRepository packs;
  private PolicyPublishRepository publish;
  private PolicyTestRunRepository runs;
  private PeriodApi periods;
  private PolicyPackSigner signer;
  private PolicyPublishLauncher launcher;
  private PolicyApprovalNotices notices;
  private PolicyApprovers approvers;
  private PolicyAudit audit;
  private PolicyApprovalService service;

  @BeforeEach
  void setUp() {
    packs = mock(PolicyPackRepository.class);
    publish = mock(PolicyPublishRepository.class);
    runs = mock(PolicyTestRunRepository.class);
    periods = mock(PeriodApi.class);
    signer = mock(PolicyPackSigner.class);
    launcher = mock(PolicyPublishLauncher.class);
    notices = mock(PolicyApprovalNotices.class);
    approvers = mock(PolicyApprovers.class);
    audit = mock(PolicyAudit.class);
    Clock clock = () -> NOW;
    service =
        new PolicyApprovalService(
            packs, publish, runs, periods, signer, launcher, notices, approvers, audit, clock);
    when(packs.findPack(tenant, pack.id())).thenReturn(Optional.of(pack));
    when(publish.storeSubmitted(any(), any(), anyLong())).thenReturn(true);
    when(publish.storeSignature(any(), any(), any(), anyLong())).thenReturn(true);
    when(publish.storeDecision(any(), any(), anyLong())).thenReturn(true);
    when(packs.storeStatus(any(), any(), anyLong())).thenReturn(true);
  }

  private PolicyPackVersion version(PolicyVersionStatus status, String hash, boolean signed) {
    return new PolicyPackVersion(
        UUID.randomUUID(),
        pack.id(),
        "2.1.0",
        status,
        null,
        "{}",
        hash,
        BigDecimal.ONE,
        runId,
        signed,
        null,
        null,
        null,
        null,
        null,
        "Ghi chú",
        NOW,
        author,
        NOW,
        author,
        4);
  }

  private void stored(PolicyPackVersion v) {
    when(packs.findVersion(tenant, pack.id(), "2.1.0")).thenReturn(Optional.of(v));
  }

  private void lastRun(PolicyPackVersion v, boolean engineMatch) {
    PolicyTestRun run =
        new PolicyTestRun(
            runId,
            v.id(),
            "CEL_JAVA",
            NOW,
            NOW,
            1,
            1,
            0,
            TestRunResult.DAT,
            engineMatch,
            null,
            null,
            author,
            1);
    when(runs.findRun(tenant, v.id(), runId)).thenReturn(Optional.of(new NumberedRun(run, 1)));
  }

  private void period(LocalDate start, PeriodStatus status) {
    when(periods.findById(tenant, PeriodId.of(periodId)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(periodId),
                    tenant,
                    "Q1-2027",
                    "Quý I/2027",
                    start,
                    start.plusMonths(3).minusDays(1),
                    status)));
  }

  private Submit submit() {
    return new Submit(periodId, List.of(new Scope("DANG", null, null)), " Áp dụng toàn tenant ");
  }

  private PolicyPublishRequest pending(PolicyPackVersion v, UUID submitter) {
    return PolicyPublishRequest.pending(
        UUID.randomUUID(),
        v.id(),
        periodId,
        List.of(new Scope("DANG", null, null)),
        null,
        submitter,
        NOW);
  }

  private Approver aal2(UUID who) {
    return new Approver(who, "urn:viettel:aal2", NOW.getEpochSecond() - 60);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @Test
  void submitMovesTestedVersionToPendingAndNotifiesOtherApprovers() {
    PolicyPackVersion v = version(PolicyVersionStatus.DAT_KIEM_THU, HASH, false);
    stored(v);
    lastRun(v, true);
    period(LocalDate.of(2027, 1, 1), PeriodStatus.MO);
    when(approvers.approvers(tenant)).thenReturn(List.of(author, approverId, approverId));

    PolicyPackVersion out = service.submit(tenant, author, pack.id(), "2.1.0", 4, submit());

    assertThat(out.status()).isEqualTo(PolicyVersionStatus.CHO_PHE_DUYET);
    assertThat(out.effectiveFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(out.rowVersion()).isEqualTo(5);
    ArgumentCaptor<PolicyPublishRequest> request =
        ArgumentCaptor.forClass(PolicyPublishRequest.class);
    verify(publish).insertRequest(eq(tenant), request.capture());
    assertThat(request.getValue().note()).isEqualTo("Áp dụng toàn tenant");
    assertThat(request.getValue().status()).isEqualTo(PolicyPublishRequest.Status.CHO_PHE_DUYET);
    verify(notices).submitted(tenant, pack.name(), out, List.of(approverId));
    verify(audit)
        .changed(
            eq(tenant),
            eq(author),
            eq("GUI_PHE_DUYET_POLICY_PACK"),
            eq(PolicyPackService.OBJECT_TYPE),
            eq("khoi-dang@2.1.0"),
            eq(v.id()),
            eq(5L));
  }

  @Test
  void submitRunsPublishGuardBeforeStoring() {
    PolicyPackVersion v = version(PolicyVersionStatus.DAT_KIEM_THU, HASH, false);
    stored(v);
    lastRun(v, true);
    period(LocalDate.of(2027, 1, 1), PeriodStatus.MO);
    service.setPublishGuard(
        (t, p, version) -> {
          throw new BusinessException("POLICY-422-PARAM-CONSTRAINT", "Ngoài khung");
        });

    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("POLICY-422-PARAM-CONSTRAINT");
    verify(publish, never()).storeSubmitted(any(), any(), anyLong());
  }

  @Test
  void submitRejectsUntestedVersionsAndBadInput() {
    PolicyPackVersion draft = version(PolicyVersionStatus.NHAP, null, false);
    stored(draft);
    lastRun(draft, true);
    period(LocalDate.of(2027, 1, 1), PeriodStatus.MO);
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-TESTED"));

    PolicyPackVersion tested = version(PolicyVersionStatus.DAT_KIEM_THU, HASH, false);
    stored(tested);
    lastRun(tested, false);
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-TESTED"));
    when(runs.findRun(tenant, tested.id(), runId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-TESTED"));

    lastRun(tested, true);
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 3, submit()))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.submit(
                    tenant, author, pack.id(), "2.1.0", 4, new Submit(periodId, List.of(), null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.submit(
                    tenant,
                    author,
                    pack.id(),
                    "2.1.0",
                    4,
                    new Submit(periodId, List.of(new Scope("KHAC", null, null)), null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    Scope dang = new Scope("DANG", null, null);
    assertThatThrownBy(
            () ->
                service.submit(
                    tenant,
                    author,
                    pack.id(),
                    "2.1.0",
                    4,
                    new Submit(periodId, List.of(dang, dang), null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.submit(
                    tenant, author, pack.id(), "2.1.0", 4, new Submit(null, List.of(dang), null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.submit(
                    tenant,
                    author,
                    pack.id(),
                    "2.1.0",
                    4,
                    new Submit(periodId, List.of(dang), "x".repeat(2001))))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    when(periods.findById(tenant, PeriodId.of(periodId))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    when(packs.findVersion(tenant, pack.id(), "2.1.0")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .isInstanceOf(NoSuchElementException.class);
    verify(publish, never()).insertRequest(any(), any());
  }

  @Test
  void submitNeedsFutureUnlockedPeriod() {
    PolicyPackVersion v = version(PolicyVersionStatus.DAT_KIEM_THU, HASH, false);
    stored(v);
    lastRun(v, true);
    period(LocalDate.of(2026, 10, 1), PeriodStatus.MO);
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-PERIOD-LOCKED"));
    period(LocalDate.of(2027, 1, 1), PeriodStatus.DA_KHOA);
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-PERIOD-LOCKED"));
    period(LocalDate.of(2027, 1, 1), PeriodStatus.MO);
    when(publish.storeSubmitted(any(), any(), anyLong())).thenReturn(false);
    assertThatThrownBy(() -> service.submit(tenant, author, pack.id(), "2.1.0", 4, submit()))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void approveSignsHashAndLaunchesPublishSaga() {
    PolicyPackVersion v = version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, false);
    stored(v);
    PolicyPublishRequest request = pending(v, author);
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(request));
    SignedHash signed =
        new SignedHash(new byte[] {1, 2, 3}, UUID.randomUUID(), NOW, new byte[] {9});
    when(signer.sign(any(), any(), any(), any(), any())).thenReturn(signed);
    when(launcher.launch(any(), any(), any(), any())).thenReturn("job-1");

    Approved out =
        service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, " Đồng ý ");

    assertThat(out.jobId()).isEqualTo("job-1");
    assertThat(out.version().signed()).isTrue();
    assertThat(out.version().approvedBy()).isEqualTo(approverId);
    assertThat(out.version().status()).isEqualTo(PolicyVersionStatus.CHO_PHE_DUYET);
    ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
    verify(signer).sign(eq(tenant), eq(approverId), eq(v.id()), hash.capture(), eq(null));
    assertThat(java.util.HexFormat.of().formatHex(hash.getValue())).isEqualTo(HASH);
    ArgumentCaptor<PolicyPublishRequest> decided =
        ArgumentCaptor.forClass(PolicyPublishRequest.class);
    verify(publish).storeDecision(eq(tenant), decided.capture(), eq(0L));
    assertThat(decided.getValue().status()).isEqualTo(PolicyPublishRequest.Status.DA_DUYET);
    assertThat(decided.getValue().decisionReason()).isEqualTo("Đồng ý");
    assertThat(signed.signature()).containsExactly(1, 2, 3);
    assertThat(signed.tsaToken()).containsExactly(9);
  }

  @Test
  void approveEnforcesFourEyesStepUpAndSigning() {
    PolicyPackVersion v = version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, false);
    stored(v);
    PolicyPublishRequest request = pending(v, author);
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(request));

    assertThatThrownBy(
            () -> service.approve(tenant, aal2(author), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-403-SAME-AUTHOR"));
    UUID submitter = UUID.randomUUID();
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(pending(v, submitter)));
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(submitter), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-403-SAME-AUTHOR"));
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(request));

    assertThatThrownBy(
            () ->
                service.approve(
                    tenant,
                    new Approver(approverId, "aal1", null),
                    pack.id(),
                    "2.1.0",
                    4,
                    null,
                    null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    tenant,
                    new Approver(approverId, "aal2", null),
                    pack.id(),
                    "2.1.0",
                    4,
                    null,
                    null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    tenant,
                    new Approver(approverId, "aal2", NOW.getEpochSecond() - 3600),
                    pack.id(),
                    "2.1.0",
                    4,
                    null,
                    null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, "x".repeat(2001)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));

    when(signer.sign(any(), any(), any(), any(), any()))
        .thenThrow(new PolicyPackSigner.SigningFailedException("lỗi", null));
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-SIGNED"));
    verify(publish, never()).storeSignature(any(), any(), any(), anyLong());

    when(publish.findPending(tenant, v.id())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-PENDING"));

    PolicyPackVersion noHash = version(PolicyVersionStatus.CHO_PHE_DUYET, null, false);
    stored(noHash);
    when(publish.findPending(tenant, noHash.id())).thenReturn(Optional.of(pending(noHash, author)));
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-SIGNED"));

    stored(version(PolicyVersionStatus.DAT_KIEM_THU, HASH, false));
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-PENDING"));
  }

  @Test
  void approveDetectsConcurrentWrites() {
    PolicyPackVersion v = version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, false);
    stored(v);
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(pending(v, author)));
    when(signer.sign(any(), any(), any(), any(), any()))
        .thenReturn(new SignedHash(new byte[] {1}, null, NOW, null));
    when(publish.storeDecision(any(), any(), anyLong())).thenReturn(false);
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .isInstanceOf(VersionConflictException.class);
    when(publish.storeSignature(any(), any(), any(), anyLong())).thenReturn(false);
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .isInstanceOf(VersionConflictException.class);
    stored(version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, true));
    when(publish.findPending(eq(tenant), any())).thenReturn(Optional.of(pending(v, author)));
    when(publish.storeSignature(any(), any(), any(), anyLong())).thenReturn(true);
    assertThatThrownBy(
            () -> service.approve(tenant, aal2(approverId), pack.id(), "2.1.0", 4, null, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-PENDING"));
  }

  @Test
  void rejectReturnsVersionToDraftAndNotifiesAuthor() {
    PolicyPackVersion v = version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, false);
    stored(v);
    UUID submitter = UUID.randomUUID();
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(pending(v, submitter)));
    String reason = "Cần bổ sung ca kiểm thử cho ngày nghỉ bù";

    PolicyPackVersion out =
        service.reject(tenant, approverId, pack.id(), "2.1.0", 4, "  " + reason + " ");

    assertThat(out.status()).isEqualTo(PolicyVersionStatus.NHAP);
    verify(notices).rejected(tenant, pack.name(), out, reason, List.of(author, submitter));

    when(publish.findPending(tenant, v.id())).thenReturn(Optional.empty());
    service.reject(tenant, approverId, pack.id(), "2.1.0", 4, reason);
    verify(notices).rejected(eq(tenant), eq(pack.name()), any(), eq(reason), eq(List.of(author)));

    assertThatThrownBy(() -> service.reject(tenant, approverId, pack.id(), "2.1.0", 4, "ngắn"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.reject(tenant, approverId, pack.id(), "2.1.0", 4, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    when(publish.findPending(tenant, v.id())).thenReturn(Optional.of(pending(v, author)));
    when(publish.storeDecision(any(), any(), anyLong())).thenReturn(false);
    assertThatThrownBy(() -> service.reject(tenant, approverId, pack.id(), "2.1.0", 4, reason))
        .isInstanceOf(VersionConflictException.class);
    when(packs.storeStatus(any(), any(), anyLong())).thenReturn(false);
    assertThatThrownBy(() -> service.reject(tenant, approverId, pack.id(), "2.1.0", 4, reason))
        .isInstanceOf(VersionConflictException.class);

    stored(version(PolicyVersionStatus.DAT_KIEM_THU, HASH, false));
    assertThatThrownBy(() -> service.reject(tenant, approverId, pack.id(), "2.1.0", 4, reason))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-PENDING"));
    stored(version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, true));
    assertThatThrownBy(() -> service.reject(tenant, approverId, pack.id(), "2.1.0", 4, reason))
        .satisfies(e -> assertThat(code(e)).isEqualTo("POLICY-409-NOT-PENDING"));
  }

  @Test
  void publishRequestDecidesOnlyOnce() {
    PolicyPackVersion v = version(PolicyVersionStatus.CHO_PHE_DUYET, HASH, false);
    PolicyPublishRequest r = pending(v, author);
    PolicyPublishRequest done =
        r.decide(PolicyPublishRequest.Status.TU_CHOI, approverId, "Chưa đủ căn cứ", NOW);
    assertThat(done.rowVersion()).isEqualTo(1);
    assertThatThrownBy(
            () -> done.decide(PolicyPublishRequest.Status.DA_DUYET, approverId, null, NOW))
        .hasMessageContaining("không còn chờ");
    assertThatThrownBy(
            () -> r.decide(PolicyPublishRequest.Status.CHO_PHE_DUYET, approverId, null, NOW))
        .hasMessageContaining("không còn chờ");
    assertThatThrownBy(
            () ->
                PolicyPublishRequest.pending(
                    UUID.randomUUID(), v.id(), periodId, List.of(), null, author, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void contentHashIgnoresKeyAndRowOrder() {
    ObjectMapper json = new ObjectMapper();
    UUID versionId = UUID.randomUUID();
    PolicyRule a =
        new PolicyRule(
            UUID.randomUUID(),
            versionId,
            "a",
            "x + 1",
            "DECIMAL",
            "{\"mode\":\"HALF_UP\",\"scale\":2}",
            "QĐ",
            1,
            "{\"p\":1,\"q\":[1,2]}",
            false);
    PolicyRule b =
        new PolicyRule(
            UUID.randomUUID(), versionId, "b", "a * 2", "DECIMAL", "{}", "", 2, "{}", true);
    DecisionTable t =
        new DecisionTable(
            UUID.randomUUID(),
            versionId,
            "xep_loai",
            "<definitions/>",
            "FIRST",
            new byte[] {1, 2},
            "QĐ Điều 11",
            "[{\"in\":{\"diem\":\"< 60\"},\"out\":\"KHTNV\"}]",
            "{}");
    String first =
        PolicyContentHash.of(
            json, "{\"metadata\":{\"id\":\"x\"},\"params\":{\"k\":1}}", List.of(a, b), List.of(t));
    PolicyRule aShuffled =
        new PolicyRule(
            UUID.randomUUID(),
            versionId,
            "a",
            "x + 1",
            "DECIMAL",
            "{\"scale\":2,\"mode\":\"HALF_UP\"}",
            "QĐ",
            1,
            "{\"q\":[1,2],\"p\":1}",
            false);
    String second =
        PolicyContentHash.of(
            json,
            "{\"params\":{\"k\":1},\"metadata\":{\"id\":\"x\"}}",
            List.of(b, aShuffled),
            List.of(t));
    assertThat(second).isEqualTo(first).hasSize(64);
    String changed =
        PolicyContentHash.of(
            json, "{\"metadata\":{\"id\":\"x\"},\"params\":{\"k\":2}}", List.of(a, b), List.of(t));
    assertThat(changed).isNotEqualTo(first);
    DecisionTable noHash =
        new DecisionTable(
            UUID.randomUUID(), versionId, "x", "<d/>", "FIRST", new byte[0], "", " ", "");
    assertThat(PolicyContentHash.of(json, "{}", List.of(), List.of(noHash))).hasSize(64);
    assertThatThrownBy(() -> PolicyContentHash.of(json, "{khong", List.of(), List.of()))
        .isInstanceOf(IllegalStateException.class);
  }
}
