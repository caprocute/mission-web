package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService.ApproveCommand;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService.Approver;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService.Outcome;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService.Signed;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalEvents;
import vn.viettel.okrkpi.task.application.plan.port.PlanNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ApprovalRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.SignedTarget;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanSignatures;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** Duyệt kế hoạch có ký số (CORE-02-09): thứ tự kiểm tra của duyệt và áp EVT-18. */
class PlanApprovalServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2027-04-10T02:00:00Z");
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID HEAD = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID SIGN = UUID.randomUUID();

  private final PlanRepository repo = mock(PlanRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final PlanSignatures signatures = mock(PlanSignatures.class);
  private final PlanApprovalEvents events = mock(PlanApprovalEvents.class);
  private final PlanNotices notices = mock(PlanNotices.class);
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  private final PlanApprovalService service =
      new PlanApprovalService(
          repo,
          periods,
          org,
          signatures,
          events,
          notices,
          publisher,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final PlanRow plan =
      new PlanRow(UUID.randomUUID(), UUID.randomUUID(), 20272, "TAP_THE", UNIT, null, null, 0);
  private List<ItemRow> items = List.of(item(3, BigDecimal.TEN));
  private VersionRow version;

  @BeforeEach
  void setUp() {
    version = version(PlanVersionStatus.CHO_DUYET, PlanContentHash.of(plan, 1, items));
    when(repo.findPlan(TENANT, plan.id())).thenReturn(Optional.of(plan));
    when(repo.lockVersion(TENANT, plan.periodBucket(), plan.id(), 1))
        .thenAnswer(i -> Optional.of(version));
    when(repo.approvalRow(eq(TENANT), anyInt(), any()))
        .thenReturn(Optional.of(new ApprovalRow(AUTHOR, null, null)));
    when(repo.items(eq(TENANT), anyInt(), any())).thenAnswer(i -> items);
    when(org.headsOf(eq(TENANT), eq(UNIT), any(LocalDate.class))).thenReturn(List.of(AUTHOR, HEAD));
    when(signatures.request(any())).thenReturn(SIGN);
    when(repo.attachSignRequest(any(), any(), any(), any(), any())).thenReturn(3L);
  }

  @Test
  void duyetHopLeTaoYeuCauKy() {
    PlanApprovalService.Started started = service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD));
    assertThat(started.signRequestId()).isEqualTo(SIGN);
    assertThat(started.rowVersion()).isEqualTo(3);
    ArgumentCaptor<PlanSignatures.Request> req =
        ArgumentCaptor.forClass(PlanSignatures.Request.class);
    verify(signatures).request(req.capture());
    assertThat(req.getValue().callerToken()).isEqualTo("t");
    assertThat(req.getValue().approver()).isEqualTo(HEAD);
    verify(repo).attachSignRequest(TENANT, plan, version, SIGN, HEAD);
  }

  @Test
  void tuChoiTheoThuTuKiemTra() {
    assertCode(
        () -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD, "FAX", "aal2", NOW)),
        PlanService.VALIDATION);
    assertCode(
        () -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD, null, "aal2", NOW)),
        PlanService.VALIDATION);
    assertThatThrownBy(() -> service.approve(TENANT, plan.id(), 1, 1, cmd(HEAD)))
        .isInstanceOf(VersionConflictException.class);
    assertCode(() -> service.approve(TENANT, plan.id(), 1, 2, cmd(AUTHOR)), "TASK-403-SAME-AUTHOR");
    assertCode(
        () -> service.approve(TENANT, plan.id(), 1, 2, cmd(UUID.randomUUID())),
        PlanReviewService.OUT_OF_SCOPE);
    assertThatThrownBy(
            () -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD, "MYSIGN", "aal1", NOW)))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT, plan.id(), 1, 2, cmd(HEAD, "MYSIGN", "aal2", NOW.minusSeconds(901))))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT,
                    plan.id(),
                    1,
                    2,
                    new ApproveCommand(approver(HEAD, "aal2", null), "MYSIGN", null, "k", "t")))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT, plan.id(), 1, 2, cmd(HEAD, "MYSIGN", "aal2", NOW.plusSeconds(60))))
        .isInstanceOf(StepUpRequiredException.class);
    verifyNoInteractions(signatures);
  }

  @Test
  void trangThaiKhongChoDuyetVaKhongTimThay() {
    version = version(PlanVersionStatus.NHAP, null);
    assertCode(
        () -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD)), PlanService.INVALID_TRANSITION);
    assertThatThrownBy(() -> service.approve(TENANT, plan.id(), 2, 2, cmd(HEAD)))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.approve(TENANT, UUID.randomUUID(), 1, 2, cmd(HEAD)))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void dongNgoaiDanhMucChuaDinhDiemVaBamLech() {
    items = List.of(item(null, BigDecimal.TEN), item(3, null));
    version = version(PlanVersionStatus.CHO_DUYET, PlanContentHash.of(plan, 1, items));
    assertThatThrownBy(() -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo(PlanService.VALIDATION);
              assertThat(e.errors()).hasSize(2);
              assertThat(e.errors().get(0).rule()).isEqualTo("BR-04-05");
            });
    items = List.of(item(3, BigDecimal.TEN));
    version = version(PlanVersionStatus.CHO_DUYET, new byte[32]);
    assertCode(
        () -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD)), PlanService.INVALID_TRANSITION);
    version = version(PlanVersionStatus.CHO_DUYET, null);
    assertCode(
        () -> service.approve(TENANT, plan.id(), 1, 2, cmd(HEAD)), PlanService.INVALID_TRANSITION);
  }

  @Test
  void apChuKyDuyetMotLanVaBoQuaSuKienLech() {
    byte[] hash = version.contentHash();
    target(version, SIGN);
    when(repo.markApproved(eq(TENANT), eq(plan), any(), eq(HEAD), any()))
        .thenReturn(new PlanRepository.Approved(UUID.randomUUID(), 4));
    when(periods.findById(eq(TENANT), any())).thenReturn(Optional.of(mock(PeriodSnapshot.class)));
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash)))).isEqualTo(Outcome.APPROVED);
    ArgumentCaptor<PlanVersionApprovedEvent> evt =
        ArgumentCaptor.forClass(PlanVersionApprovedEvent.class);
    verify(events).approved(evt.capture());
    assertThat(evt.getValue().signRequestId()).isEqualTo(SIGN);
    assertThat(evt.getValue().ownerId()).isEqualTo(UNIT);
    assertThat(evt.getValue().aggregateVersion()).isEqualTo(4);
    verify(notices).approved(eq(TENANT), any(), any(), eq(HEAD));
    verify(publisher).publishEvent(evt.getValue());

    target(version(PlanVersionStatus.DA_DUYET, hash), SIGN);
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash))))
        .isEqualTo(Outcome.ALREADY_APPROVED);
    target(version, UUID.randomUUID());
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash)))).isEqualTo(Outcome.IGNORED);
    target(version(PlanVersionStatus.TRA_LAI, hash), SIGN);
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash)))).isEqualTo(Outcome.IGNORED);
    target(version, SIGN);
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(new byte[32]))))
        .isEqualTo(Outcome.IGNORED);
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash, hash))))
        .isEqualTo(Outcome.IGNORED);
    target(version(PlanVersionStatus.CHO_DUYET, null), SIGN);
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash)))).isEqualTo(Outcome.IGNORED);
    when(repo.versionById(TENANT, version.id(), true)).thenReturn(Optional.empty());
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(hash)))).isEqualTo(Outcome.IGNORED);
    verify(repo, never()).markApproved(any(), any(), any(), eq(AUTHOR), any());
  }

  @Test
  void keHoachCaNhanGhiChuSoHuuLaNguoi() {
    UUID person = UUID.randomUUID();
    PlanRow personal =
        new PlanRow(plan.id(), plan.periodId(), 20272, "CA_NHAN", UNIT, person, null, 0);
    when(repo.versionById(TENANT, version.id(), true))
        .thenReturn(
            Optional.of(new SignedTarget(personal, version, new ApprovalRow(AUTHOR, SIGN, null))));
    when(repo.markApproved(any(), any(), any(), any(), any()))
        .thenReturn(new PlanRepository.Approved(null, 4));
    when(periods.findById(eq(TENANT), any())).thenReturn(Optional.empty());
    assertThat(service.onSigned(TENANT, signed(SIGN, List.of(version.contentHash()))))
        .isEqualTo(Outcome.APPROVED);
    ArgumentCaptor<PlanVersionApprovedEvent> evt =
        ArgumentCaptor.forClass(PlanVersionApprovedEvent.class);
    verify(events).approved(evt.capture());
    assertThat(evt.getValue().ownerId()).isEqualTo(person);
    verify(notices).approved(eq(TENANT), any(), eq(""), eq(HEAD));
  }

  @Test
  void nguonKyVaTraCuu() {
    when(repo.versionById(TENANT, version.id(), false))
        .thenReturn(
            Optional.of(new SignedTarget(plan, version, new ApprovalRow(AUTHOR, SIGN, null))));
    PlanApprovalService.SignSource source = service.signSource(TENANT, version.id()).orElseThrow();
    assertThat(source.ready()).isTrue();
    assertThat(source.signers()).containsExactly(HEAD);
    assertThat(source.contentHash()).isEqualTo(version.contentHash());
    when(repo.versionById(TENANT, version.id(), false))
        .thenReturn(
            Optional.of(
                new SignedTarget(
                    plan,
                    version(PlanVersionStatus.NHAP, null),
                    new ApprovalRow(AUTHOR, null, null))));
    assertThat(service.signSource(TENANT, version.id()).orElseThrow().ready()).isFalse();
    when(repo.signRequestInitiator(TENANT, SIGN)).thenReturn(Optional.of(HEAD));
    assertThat(service.signRequestInitiator(TENANT, SIGN)).contains(HEAD);
    when(repo.approvalRow(TENANT, 20272, version.id()))
        .thenReturn(Optional.of(new ApprovalRow(AUTHOR, SIGN, null)));
    assertThat(service.signRequestOf(TENANT, 20272, version.id())).contains(SIGN);

    PlanApprovalService noOrg =
        new PlanApprovalService(
            repo,
            periods,
            null,
            signatures,
            events,
            notices,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(repo.versionById(TENANT, version.id(), false))
        .thenReturn(
            Optional.of(new SignedTarget(plan, version, new ApprovalRow(AUTHOR, SIGN, null))));
    assertThat(noOrg.signSource(TENANT, version.id()).orElseThrow().signers()).isEmpty();
    assertThat(noOrg.approve(TENANT, plan.id(), 1, 2, cmd(HEAD)).signRequestId()).isEqualTo(SIGN);
    assertThatThrownBy(() -> new Approver(null, "aal2", 1L))
        .isInstanceOf(NullPointerException.class);
    verify(repo, never()).markApproved(any(), any(), any(), any(), any());
    verify(signatures).request(any());
    verify(repo).attachSignRequest(eq(TENANT), eq(plan), any(), eq(SIGN), eq(HEAD));
  }

  private void target(VersionRow v, UUID signRequest) {
    when(repo.versionById(TENANT, version.id(), true))
        .thenReturn(
            Optional.of(new SignedTarget(plan, v, new ApprovalRow(AUTHOR, signRequest, null))));
  }

  private Signed signed(UUID signRequest, List<byte[]> hashes) {
    return new Signed(signRequest, version.id(), hashes, HEAD, NOW);
  }

  private static ApproveCommand cmd(UUID person) {
    return cmd(person, "MYSIGN", "aal2", NOW.minusSeconds(60));
  }

  private static ApproveCommand cmd(UUID person, String method, String acr, Instant authTime) {
    return new ApproveCommand(
        approver(person, acr, authTime.getEpochSecond()), method, null, "k", "t");
  }

  private static Approver approver(UUID person, String acr, Long authTime) {
    return new Approver(person, acr, authTime);
  }

  private VersionRow version(PlanVersionStatus status, byte[] hash) {
    UUID id = version == null ? UUID.randomUUID() : version.id();
    return new VersionRow(id, plan.id(), 1, status, false, NOW, null, hash, null, null, 1, 2);
  }

  private static ItemRow item(Integer difficulty, BigDecimal score) {
    return new ItemRow(
        UUID.randomUUID(),
        1,
        null,
        null,
        null,
        "Khảo sát",
        "THEN_CHOT",
        new PlanRepository.Assignee("UNIT", UNIT, ""),
        List.of(),
        "Báo cáo",
        BigDecimal.ONE,
        "báo cáo",
        difficulty,
        score,
        LocalDate.parse("2027-06-01"),
        null,
        null,
        null);
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode().code()).isEqualTo(code));
  }
}
