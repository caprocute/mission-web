package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** CORE-03-06: nhánh kiểm của mở nghiệm thu (giả lập cổng). */
class EvidenceReviewServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID LEAD = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final EvidenceActor REVIEWER = new EvidenceActor(ASSIGNER, Set.of("manager"));

  private EvidenceSubmissionRepository repo;
  private EvidenceLifecycleService lifecycle;
  private TaskApi tasks;
  private EvidenceEvents events;
  private List<AuditEvent> audits;
  private EvidenceReviewService service;

  private final UUID taskId = UUID.randomUUID();
  private final UUID evidenceId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID objectId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(EvidenceSubmissionRepository.class);
    lifecycle = mock(EvidenceLifecycleService.class);
    tasks = mock(TaskApi.class);
    events = mock(EvidenceEvents.class);
    audits = new ArrayList<>();
    AuditLogger audit = audits::add;
    service =
        new EvidenceReviewService(
            repo, lifecycle, tasks, new EvidenceIntegrityAlarm(events, audit), audit);
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task(null)));
    when(repo.findEvidence(TENANT, evidenceId))
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
                    1,
                    "DA_NOP",
                    false,
                    LEAD,
                    null,
                    3)));
    version("DA_NOP", false);
    files(1, sha(1), "SACH");
    when(tasks.listForPerson(eq(TENANT), any(), eq(ASSIGNER), any(), anyInt()))
        .thenReturn(List.of());
  }

  private TaskSummary task(UUID acceptor) {
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
        acceptor,
        LEAD,
        LocalDate.of(2027, 3, 31),
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1);
  }

  private TaskSummary other() {
    return new TaskSummary(
        UUID.randomUUID(),
        PERIOD,
        20271,
        "NV-2",
        "Khác",
        "DANG_THUC_HIEN",
        false,
        false,
        LEAD,
        null,
        ASSIGNER,
        null,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1);
  }

  private void version(String status, boolean e2ee) {
    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(
            Optional.of(
                new VersionView(
                    versionId,
                    evidenceId,
                    taskId,
                    PERIOD,
                    20271,
                    1,
                    status,
                    "TAI_LEN",
                    null,
                    "SACH",
                    false,
                    e2ee,
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
                    4)));
  }

  private void files(int storedVersion, byte[] storedSha, String scan) {
    when(repo.filesOf(TENANT, 20271, versionId))
        .thenReturn(
            List.of(
                new FileView(
                    new FileRow(objectId, 1, 1, sha(1), scan, null, null),
                    new StoredObject(
                        objectId,
                        storedVersion,
                        storedSha,
                        10L,
                        "application/pdf",
                        false,
                        scan,
                        java.time.Instant.EPOCH))));
  }

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
  }

  private String code(Runnable r) {
    try {
      r.run();
      return null;
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
  }

  @Test
  void moLanDauChuyenTrangThaiVaGhiNhatKy() {
    assertThat(service.openReview(TENANT, evidenceId, 1, 4, REVIEWER)).isTrue();
    verify(lifecycle).openReview(TENANT, versionId, 4, REVIEWER);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).type()).isEqualTo(AuditEventType.SENSITIVE_DATA_ACCESS);
    assertThat(audits.get(0).classification()).isEqualTo(Classification.C2);
  }

  @Test
  void moLaiKhongDoiTrangThai() {
    version("DANG_NGHIEM_THU", true);
    assertThat(service.openReview(TENANT, evidenceId, 1, 4, REVIEWER)).isFalse();
    verify(lifecycle, never()).openReview(any(), any(), anyLong(), any());
    assertThat(audits.get(0).classification()).isEqualTo(Classification.C3);
  }

  @Test
  void nguoiNopVaNguoiPhoiHopKhongDuocMo() {
    EvidenceActor lead = new EvidenceActor(LEAD, Set.of("member"));
    assertThat(code(() -> service.openReview(TENANT, evidenceId, 1, 4, lead)))
        .isEqualTo("PRODUCT-403-SELF-REVIEW");
    // Người giao cũng là người phối hợp (trang thứ hai của danh sách việc).
    List<TaskSummary> full = new ArrayList<>();
    for (int i = 0; i < TaskApi.MAX_LIMIT; i++) {
      full.add(other());
    }
    when(tasks.listForPerson(eq(TENANT), any(), eq(ASSIGNER), eq(null), anyInt())).thenReturn(full);
    when(tasks.listForPerson(
            eq(TENANT), any(), eq(ASSIGNER), eq(full.get(full.size() - 1).id()), anyInt()))
        .thenReturn(List.of(task(null)));
    assertThat(code(() -> service.openReview(TENANT, evidenceId, 1, 4, REVIEWER)))
        .isEqualTo("PRODUCT-403-SELF-REVIEW");
  }

  @Test
  void nguoiNghiemThuChiDinhKhacNguoiGiao() {
    UUID acceptor = UUID.randomUUID();
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task(acceptor)));
    assertThat(code(() -> service.openReview(TENANT, evidenceId, 1, 4, REVIEWER)))
        .isEqualTo("PRODUCT-403-SELF-REVIEW");
    when(tasks.listForPerson(eq(TENANT), any(), eq(acceptor), any(), anyInt()))
        .thenReturn(List.of());
    assertThat(
            service.openReview(
                TENANT, evidenceId, 1, 4, new EvidenceActor(acceptor, Set.of("manager"))))
        .isTrue();
  }

  @Test
  void trangThaiPhienBanVaKhongTimThay() {
    version("NHAP", false);
    assertThat(code(() -> service.openReview(TENANT, evidenceId, 1, 4, REVIEWER)))
        .isEqualTo("PRODUCT-409-INVALID-STATE");
    version("DA_NOP", false);
    assertThatThrownBy(() -> service.openReview(TENANT, evidenceId, 1, 3, REVIEWER))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.openReview(TENANT, evidenceId, 2, 4, REVIEWER))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.openReview(TENANT, UUID.randomUUID(), 1, 4, REVIEWER))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void tepChuaSachBiChan() {
    files(1, sha(1), "NHIEM");
    assertThat(code(() -> service.openReview(TENANT, evidenceId, 1, 4, REVIEWER)))
        .isEqualTo("PRODUCT-422-NOT-CLEAN");
    verify(lifecycle, never()).openReview(any(), any(), anyLong(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void tepBiDoiThiCanhBaoVaChan() {
    files(2, sha(9), "CHO_QUET");
    assertThatThrownBy(() -> service.openReview(TENANT, evidenceId, 1, 4, REVIEWER))
        .isInstanceOf(EvidenceReviewService.IntegrityFailedException.class)
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("PRODUCT-409-INTEGRITY-FAILED");
    ArgumentCaptor<List<UUID>> to = ArgumentCaptor.forClass(List.class);
    verify(events)
        .integrityFailed(
            eq(TENANT), to.capture(), eq("NV-1"), eq(evidenceId), eq(1), eq(ASSIGNER), eq(4L));
    assertThat(to.getValue()).containsExactly(ASSIGNER);
    assertThat(audits)
        .singleElement()
        .extracting(AuditEvent::outcome)
        .isEqualTo(AuditOutcome.DENIED);
    verify(lifecycle, never()).openReview(any(), any(), anyLong(), any());

    // Người nghiệm thu được chỉ định khác người giao: cả hai nhận cảnh báo.
    UUID acceptor = UUID.randomUUID();
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task(acceptor)));
    when(tasks.listForPerson(eq(TENANT), any(), eq(acceptor), any(), anyInt()))
        .thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.openReview(
                    TENANT, evidenceId, 1, 4, new EvidenceActor(acceptor, Set.of("manager"))))
        .isInstanceOf(EvidenceReviewService.IntegrityFailedException.class);
    verify(events)
        .integrityFailed(
            eq(TENANT), to.capture(), eq("NV-1"), eq(evidenceId), eq(1), eq(acceptor), eq(4L));
    assertThat(to.getValue()).containsExactly(acceptor, ASSIGNER);
  }

  @Test
  void permittedOnFollowsVersionStatusAndRoles() {
    version("NHAP", false);
    VersionView draft = repo.findVersion(TENANT, 20271, evidenceId, 1).orElseThrow();
    assertThat(service.permittedOn(TENANT, draft, LEAD))
        .containsExactly(EvidenceReviewService.SOAN, EvidenceReviewService.NOP);
    assertThat(service.permittedOn(TENANT, draft, ASSIGNER)).isEmpty();
    assertThat(service.permittedOn(TENANT, draft, null)).isEmpty();

    version("DA_NOP", false);
    VersionView submitted = repo.findVersion(TENANT, 20271, evidenceId, 1).orElseThrow();
    assertThat(service.permittedOn(TENANT, submitted, ASSIGNER))
        .containsExactly(EvidenceReviewService.MO);
    assertThat(service.permittedOn(TENANT, submitted, LEAD)).isEmpty();

    version("DANG_NGHIEM_THU", false);
    VersionView reviewing = repo.findVersion(TENANT, 20271, evidenceId, 1).orElseThrow();
    assertThat(service.permittedOn(TENANT, reviewing, ASSIGNER))
        .containsExactly(
            EvidenceReviewService.MO,
            EvidenceReviewService.XAC_NHAN,
            EvidenceReviewService.BO_SUNG_TU_CHOI);

    when(tasks.find(TENANT, taskId)).thenReturn(Optional.empty());
    assertThat(service.permittedOn(TENANT, reviewing, ASSIGNER)).isEmpty();
  }
}
