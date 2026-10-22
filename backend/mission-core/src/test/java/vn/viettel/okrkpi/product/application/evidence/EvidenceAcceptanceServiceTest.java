package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAcceptanceService.AcceptInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAcceptanceService.Decision;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository.NewAcceptance;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository.SealedComment;
import vn.viettel.okrkpi.product.application.evidence.port.CommentSealer;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.domain.evidence.CriterionResult;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.Outcome;
import vn.viettel.okrkpi.task.api.TaskApi.TransitionOutcome;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** CORE-03-07: nhánh kiểm của quyết định nghiệm thu (giả lập cổng). */
class EvidenceAcceptanceServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID LEAD = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2027-02-01T08:00:00Z");
  static final EvidenceActor FRESH =
      new EvidenceActor(ASSIGNER, Set.of("manager"), "aal2", NOW.getEpochSecond() - 60);
  static final List<CriterionResult> CRITERIA = List.of(new CriterionResult("TC-1", true, null));

  private EvidenceSubmissionRepository evidences;
  private AcceptanceRepository acceptances;
  private EvidenceLifecycleService lifecycle;
  private TaskApi tasks;
  private EvidenceEvents events;
  private List<AuditEvent> audits;
  private EvidenceAcceptanceService service;

  private final UUID taskId = UUID.randomUUID();
  private final UUID evidenceId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID objectId = UUID.randomUUID();
  private final UUID dekId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    evidences = mock(EvidenceSubmissionRepository.class);
    acceptances = mock(AcceptanceRepository.class);
    lifecycle = mock(EvidenceLifecycleService.class);
    tasks = mock(TaskApi.class);
    events = mock(EvidenceEvents.class);
    audits = new ArrayList<>();
    AuditLogger audit = audits::add;
    EvidenceReviewService reviews =
        new EvidenceReviewService(
            evidences, lifecycle, tasks, new EvidenceIntegrityAlarm(events, audit), audit);
    CommentSealer sealer =
        (tenant, id, plain) ->
            plain == null || plain.isBlank() ? null : new SealedComment(new byte[] {9}, dekId);
    service =
        new EvidenceAcceptanceService(
            evidences,
            acceptances,
            lifecycle,
            reviews,
            tasks,
            events,
            sealer,
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task()));
    when(tasks.listForPerson(eq(TENANT), any(), any(), any(), anyInt())).thenReturn(List.of());
    when(evidences.findEvidence(TENANT, evidenceId))
        .thenReturn(
            Optional.of(
                new EvidenceView(
                    evidenceId,
                    PERIOD,
                    20271,
                    UUID.randomUUID(),
                    taskId,
                    "Báo cáo",
                    "TAI_LEN",
                    null,
                    2,
                    "DANG_NGHIEM_THU",
                    false,
                    LEAD,
                    null,
                    3)));
    version("DANG_NGHIEM_THU");
    when(evidences.filesOf(TENANT, 20271, versionId))
        .thenReturn(
            List.of(
                new FileView(
                    new FileRow(objectId, 1, 1, sha(1), "SACH", null, null),
                    new StoredObject(
                        objectId, 1, sha(1), 10L, "application/pdf", false, "SACH", NOW))));
    when(acceptances.priorCriteria(any(), anyInt(), any(), anyInt())).thenReturn(Set.of());
  }

  private TaskSummary task() {
    return new TaskSummary(
        taskId,
        PERIOD,
        20271,
        "NV-1",
        "Nhiệm vụ",
        "CHO_NGHIEM_THU",
        false,
        false,
        ASSIGNER,
        null,
        LEAD,
        LocalDate.of(2027, 3, 31),
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1);
  }

  private void version(String status) {
    when(evidences.findVersion(TENANT, 20271, evidenceId, 2))
        .thenReturn(
            Optional.of(
                new VersionView(
                    versionId,
                    evidenceId,
                    taskId,
                    PERIOD,
                    20271,
                    2,
                    status,
                    "TAI_LEN",
                    null,
                    "SACH",
                    false,
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    LEAD,
                    null,
                    null,
                    null,
                    null,
                    5)));
  }

  private EvidenceLifecycleService.Result result(EvidenceStatus status, String taskStatus) {
    EvidenceVersion v = mock(EvidenceVersion.class);
    when(v.status()).thenReturn(status);
    TaskSummary t = mock(TaskSummary.class);
    when(t.status()).thenReturn(taskStatus);
    return new EvidenceLifecycleService.Result(
        v, 6, Optional.empty(), Optional.of(new TransitionOutcome(Outcome.APPLIED, t)));
  }

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
  }

  private static String code(Runnable r) {
    try {
      r.run();
      return null;
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
  }

  private AcceptInput input(String quality, String comment) {
    return new AcceptInput(new BigDecimal(quality), CRITERIA, null, null, comment);
  }

  @Test
  void xacNhanGhiNghiemThuVaEvt08() {
    EvidenceLifecycleService.Result res1 = result(EvidenceStatus.DA_XAC_NHAN, "HOAN_THANH");
    when(lifecycle.acceptVersion(TENANT, versionId, 5, FRESH)).thenReturn(res1);
    Decision d =
        service.acceptVersion(TENANT, evidenceId, 2, 5, input("0.75", "Cần bổ sung bảng"), FRESH);
    assertThat(d.versionStatus()).isEqualTo("DA_XAC_NHAN");
    assertThat(d.taskStatusChanged()).isEqualTo("HOAN_THANH");
    assertThat(d.rowVersion()).isEqualTo(6);
    assertThat(d.acceptance().comment()).isEqualTo("Cần bổ sung bảng");
    ArgumentCaptor<NewAcceptance> row = ArgumentCaptor.forClass(NewAcceptance.class);
    verify(acceptances).insertAcceptance(eq(TENANT), row.capture());
    assertThat(row.getValue().comment().dekId()).isEqualTo(dekId);
    assertThat(row.getValue().revisionCause()).isNull();
    ArgumentCaptor<EvidenceEvents.Accepted> evt =
        ArgumentCaptor.forClass(EvidenceEvents.Accepted.class);
    verify(events).accepted(evt.capture());
    assertThat(evt.getValue().qualityLevel()).isEqualByComparingTo("0.75");
    assertThat(evt.getValue().aggregateVersion()).isEqualTo(6);
    assertThat(audits).hasSize(1);
  }

  @Test
  void xacNhanSauYeuCauBoSungCanNguyenNhan() {
    EvidenceLifecycleService.Result res2 = result(EvidenceStatus.DA_XAC_NHAN, "HOAN_THANH");
    when(acceptances.priorRevisions(TENANT, 20271, evidenceId, 2)).thenReturn(1);
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), FRESH)))
        .isEqualTo("PRODUCT-422-REVISION-CAUSE-REQUIRED");
    when(lifecycle.acceptVersion(TENANT, versionId, 5, FRESH)).thenReturn(res2);
    Decision d =
        service.acceptVersion(
            TENANT,
            evidenceId,
            2,
            5,
            new AcceptInput(BigDecimal.ONE, CRITERIA, "THE_THUC", true, null),
            FRESH);
    assertThat(d.acceptance().revisionCount()).isEqualTo(1);
    assertThat(d.acceptance().objectiveRevision()).isTrue();
    assertThat(d.acceptance().comment()).isNull();
  }

  @Test
  void canAal2TrongMuoiLamPhut() {
    EvidenceActor noAcr = new EvidenceActor(ASSIGNER, Set.of("manager"));
    assertThatThrownBy(
            () -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), noAcr))
        .isInstanceOf(StepUpRequiredException.class);
    EvidenceActor stale =
        new EvidenceActor(ASSIGNER, Set.of("manager"), "aal2", NOW.getEpochSecond() - 16 * 60);
    assertThatThrownBy(
            () -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), stale))
        .isInstanceOf(StepUpRequiredException.class);
    EvidenceActor noTime = new EvidenceActor(ASSIGNER, Set.of("manager"), "aal2", null);
    assertThatThrownBy(
            () -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), noTime))
        .isInstanceOf(StepUpRequiredException.class);
    verify(lifecycle, never()).acceptVersion(any(), any(), anyLong(), any());
  }

  @Test
  void kiemNguoiTrangThaiPhienBanVaNoiDung() {
    EvidenceActor lead = new EvidenceActor(LEAD, Set.of(), "aal2", NOW.getEpochSecond());
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), lead)))
        .isEqualTo("PRODUCT-403-SELF-REVIEW");
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("0.5", ""), FRESH)))
        .isEqualTo("PRODUCT-422-COMMENT-REQUIRED");
    assertThatThrownBy(
            () -> service.acceptVersion(TENANT, evidenceId, 2, 4, input("1", null), FRESH))
        .isInstanceOf(VersionConflictException.class);
    version("DA_NOP");
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), FRESH)))
        .isEqualTo("PRODUCT-409-INVALID-STATE");
    assertThatThrownBy(
            () -> service.acceptVersion(TENANT, evidenceId, 3, 5, input("1", null), FRESH))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void tepBiDoiThiKhongXacNhan() {
    when(evidences.filesOf(TENANT, 20271, versionId))
        .thenReturn(
            List.of(
                new FileView(
                    new FileRow(objectId, 1, 1, sha(1), "SACH", null, null),
                    new StoredObject(
                        objectId, 2, sha(2), 10L, "application/pdf", false, "CHO_QUET", NOW))));
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), FRESH)))
        .isEqualTo("PRODUCT-409-INTEGRITY-FAILED");
    verify(lifecycle, never()).acceptVersion(any(), any(), anyLong(), any());
  }

  @Test
  void yeuCauBoSungVaTuChoi() {
    assertThat(code(() -> service.requestRevision(TENANT, evidenceId, 2, 5, List.of(), " ", FRESH)))
        .isEqualTo("PRODUCT-422-COMMENT-REQUIRED");
    EvidenceLifecycleService.Result res3 = result(EvidenceStatus.YEU_CAU_BO_SUNG, "DANG_THUC_HIEN");
    when(lifecycle.requestRevision(eq(TENANT), eq(versionId), eq(5L), anyString(), eq(FRESH)))
        .thenReturn(res3);
    Decision d =
        service.requestRevision(TENANT, evidenceId, 2, 5, null, "Thiếu số liệu chi bộ 3", FRESH);
    assertThat(d.versionStatus()).isEqualTo("YEU_CAU_BO_SUNG");
    assertThat(d.acceptance().revisionCount()).isEqualTo(1);
    verify(lifecycle)
        .requestRevision(TENANT, versionId, 5, EvidenceAcceptanceService.REASON_REVISION, FRESH);
    verify(events).returned(TENANT, LEAD, false, "NV-1", evidenceId, 2, ASSIGNER, 6L);

    EvidenceLifecycleService.Result res4 = result(EvidenceStatus.TU_CHOI, "DANG_THUC_HIEN");
    when(lifecycle.rejectVersion(eq(TENANT), eq(versionId), eq(5L), anyString(), eq(FRESH)))
        .thenReturn(res4);
    Decision r = service.rejectVersion(TENANT, evidenceId, 2, 5, CRITERIA, "Sai tài liệu", FRESH);
    assertThat(r.versionStatus()).isEqualTo("TU_CHOI");
    assertThat(r.acceptance().revisionCount()).isZero();
    verify(events).returned(TENANT, LEAD, true, "NV-1", evidenceId, 2, ASSIGNER, 6L);
  }

  @Test
  void nghiemThuThayTheoUyQuyenDangHieuLuc() {
    UUID substitute = UUID.randomUUID();
    UUID grantId = UUID.randomUUID();
    EvidenceActor sub =
        new EvidenceActor(substitute, Set.of("manager"), "aal2", NOW.getEpochSecond());
    // Không có uỷ quyền: người khác người nghiệm thu được chỉ định bị chặn.
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), sub)))
        .isEqualTo("PRODUCT-403-SELF-REVIEW");

    vn.viettel.okrkpi.config.api.DelegationApi delegations =
        mock(vn.viettel.okrkpi.config.api.DelegationApi.class);
    when(delegations.activeFor(
            eq(TENANT),
            eq(vn.viettel.okrkpi.config.api.DelegationApi.Kind.NGHIEM_THU_THAY),
            eq(ASSIGNER),
            eq(substitute),
            eq(null),
            eq(null),
            eq(NOW)))
        .thenReturn(
            Optional.of(
                new vn.viettel.okrkpi.config.api.DelegationApi.DelegationGrant(
                    grantId,
                    vn.viettel.okrkpi.config.api.DelegationApi.Kind.NGHIEM_THU_THAY,
                    ASSIGNER,
                    substitute,
                    UUID.randomUUID(),
                    null,
                    NOW.minusSeconds(3600),
                    NOW.plusSeconds(3600))));
    service.setAuthority(
        new ReviewerAuthority(tasks, delegations, Clock.fixed(NOW, ZoneOffset.UTC)));
    EvidenceLifecycleService.Result res = result(EvidenceStatus.DA_XAC_NHAN, "HOAN_THANH");
    when(lifecycle.acceptVersion(TENANT, versionId, 5, sub)).thenReturn(res);
    Decision d = service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), sub);
    assertThat(d.acceptance().reviewerId()).isEqualTo(substitute);
    AuditEvent ledger = audits.get(audits.size() - 1);
    assertThat(ledger.attributes())
        .containsEntry("nghiemThuThay", "true")
        .containsEntry("onBehalfOf", ASSIGNER.toString())
        .containsEntry("delegationId", grantId.toString());

    // Người làm thay vẫn phải độc lập: người chủ trì có uỷ quyền cũng không tự nghiệm thu.
    when(delegations.activeFor(any(), any(), eq(ASSIGNER), eq(LEAD), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new vn.viettel.okrkpi.config.api.DelegationApi.DelegationGrant(
                    grantId,
                    vn.viettel.okrkpi.config.api.DelegationApi.Kind.NGHIEM_THU_THAY,
                    ASSIGNER,
                    LEAD,
                    UUID.randomUUID(),
                    null,
                    NOW,
                    NOW)));
    EvidenceActor lead = new EvidenceActor(LEAD, Set.of(), "aal2", NOW.getEpochSecond());
    assertThat(code(() -> service.acceptVersion(TENANT, evidenceId, 2, 5, input("1", null), lead)))
        .isEqualTo("PRODUCT-403-SELF-REVIEW");
  }
}
