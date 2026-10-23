package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.DeliverableInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.DraftInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.EvidenceInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.FileInput;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DeliverableView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DraftRef;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.Duplicate;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.Outcome;
import vn.viettel.okrkpi.task.api.TaskApi.TransitionOutcome;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** CORE-03-03: nhánh kiểm của dịch vụ nộp minh chứng và bản chiếu đối tượng (giả lập cổng). */
class EvidenceSubmissionServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID LEAD = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final EvidenceActor MEMBER = new EvidenceActor(LEAD, Set.of("member"));
  static final Instant NOW = Instant.parse("2027-02-01T08:00:00Z");

  private EvidenceSubmissionRepository repo;
  private EvidenceLifecycleService lifecycle;
  private PeriodApi periods;
  private TaskApi tasks;
  private EvidenceEvents events;
  private EvidenceSubmissionService service;

  private final UUID taskId = UUID.randomUUID();
  private final UUID evidenceId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID objectId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(EvidenceSubmissionRepository.class);
    lifecycle = mock(EvidenceLifecycleService.class);
    periods = mock(PeriodApi.class);
    tasks = mock(TaskApi.class);
    events = mock(EvidenceEvents.class);
    service =
        new EvidenceSubmissionService(
            repo, lifecycle, periods, tasks, events, Clock.fixed(NOW, ZoneOffset.UTC));
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task("DANG_THUC_HIEN", LEAD, null)));
  }

  private TaskSummary task(String status, UUID lead, UUID acceptor) {
    return new TaskSummary(
        taskId,
        PERIOD,
        20271,
        "NV-1",
        "Nhiệm vụ",
        status,
        false,
        false,
        ASSIGNER,
        acceptor,
        lead,
        LocalDate.of(2027, 3, 31),
        BigDecimal.ONE,
        BigDecimal.ZERO,
        1);
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

  private EvidenceView evidence() {
    return new EvidenceView(
        evidenceId,
        PERIOD,
        20271,
        UUID.randomUUID(),
        taskId,
        "Báo cáo",
        "TAI_LEN",
        null,
        1,
        "NHAP",
        false,
        null,
        null,
        1);
  }

  private VersionView version(String status, long rowVersion) {
    return new VersionView(
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
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        rowVersion);
  }

  private StoredObject stored(int b) {
    return new StoredObject(objectId, 1, sha(b), 10L, "application/pdf", false, "SACH", NOW);
  }

  private static DraftInput draft(List<FileInput> files) {
    return new DraftInput("TAI_LEN", null, files, false, null, null, null, null, null, List.of());
  }

  // ---------------------------------------------------------------- sản phẩm

  @Test
  void khaiBaoSanPham() {
    DeliverableInput in =
        new DeliverableInput(taskId, " Báo cáo ", "BAO_CAO", "báo cáo", BigDecimal.ONE);
    DeliverableView view =
        new DeliverableView(
            UUID.randomUUID(),
            PERIOD,
            20271,
            taskId,
            "Báo cáo",
            "BAO_CAO",
            "báo cáo",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "CHUA_CO",
            0);
    when(repo.findDeliverable(any(), any())).thenReturn(Optional.of(view));
    assertThat(service.createDeliverable(TENANT, in, MEMBER)).isEqualTo(view);

    UUID missing = UUID.randomUUID();
    assertThat(
            code(
                () ->
                    service.createDeliverable(
                        TENANT,
                        new DeliverableInput(missing, "x", "BAO_CAO", "x", BigDecimal.ONE),
                        MEMBER)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.createDeliverable(
                        TENANT, in, new EvidenceActor(UUID.randomUUID(), Set.of()))))
        .isEqualTo("PRODUCT-403-NOT-TASK-OWNER");
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task("HOAN_THANH", LEAD, null)));
    assertThat(code(() -> service.createDeliverable(TENANT, in, MEMBER)))
        .isEqualTo("PRODUCT-422-TASK-NOT-OPEN");
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task("DA_GIAO", LEAD, null)));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(any(), any());
    assertThatThrownBy(() -> service.createDeliverable(TENANT, in, MEMBER))
        .isInstanceOf(PeriodLockedException.class);
  }

  @Test
  void taoMinhChung() {
    UUID deliverable = UUID.randomUUID();
    when(repo.findDeliverable(TENANT, deliverable))
        .thenReturn(
            Optional.of(
                new DeliverableView(
                    deliverable,
                    PERIOD,
                    20271,
                    taskId,
                    "Báo cáo",
                    "BAO_CAO",
                    "báo cáo",
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    "CHUA_CO",
                    0)));
    when(repo.findEvidence(any(), any())).thenReturn(Optional.of(evidence()));
    EvidenceInput in = new EvidenceInput(deliverable, "Báo cáo", "TAI_LEN", " ");
    assertThat(service.createEvidence(TENANT, in, MEMBER).evidence().id()).isEqualTo(evidenceId);
    assertThat(
            code(
                () ->
                    service.createEvidence(
                        TENANT,
                        new EvidenceInput(UUID.randomUUID(), "x", "TAI_LEN", null),
                        MEMBER)))
        .isEqualTo("COMMON-422-VALIDATION");
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task("DA_GIAO", LEAD, null)));
    assertThat(code(() -> service.createEvidence(TENANT, in, MEMBER)))
        .isEqualTo("PRODUCT-409-TASK-STATE");
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(any(), any());
    when(tasks.find(TENANT, taskId)).thenReturn(Optional.of(task("DANG_THUC_HIEN", LEAD, null)));
    assertThatThrownBy(() -> service.createEvidence(TENANT, in, MEMBER))
        .isInstanceOf(PeriodLockedException.class);
  }

  // ---------------------------------------------------------------- phiên bản

  @Test
  void taoPhienBanVaLuuNhap() {
    when(repo.lockEvidence(TENANT, evidenceId)).thenReturn(Optional.of(evidence()));
    when(repo.findEvidence(TENANT, evidenceId)).thenReturn(Optional.of(evidence()));
    when(repo.latestVersion(TENANT, 20271, evidenceId)).thenReturn(Optional.of(version("NHAP", 1)));
    assertThat(code(() -> service.createVersion(TENANT, evidenceId, draft(List.of()), MEMBER)))
        .isEqualTo("PRODUCT-409-OPEN-VERSION-EXISTS");

    when(repo.latestVersion(TENANT, 20271, evidenceId)).thenReturn(Optional.empty());
    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("NHAP", 0)));
    when(repo.storedObjects(any(), any())).thenReturn(Map.of(objectId, stored(1)));
    UUID other = UUID.randomUUID();
    when(repo.firstDuplicate(any(), any(), eq(taskId)))
        .thenReturn(Optional.of(new Duplicate(other, UUID.randomUUID(), NOW)));
    when(repo.versionRef(TENANT, other))
        .thenReturn(Optional.of(new Duplicate(other, UUID.randomUUID(), NOW)));
    FileRow row = new FileRow(objectId, 1, 1, sha(1), "SACH", other, null);
    when(repo.filesOf(TENANT, 20271, versionId)).thenReturn(List.of(new FileView(row, stored(1))));
    var created =
        service.createVersion(
            TENANT, evidenceId, draft(List.of(new FileInput(objectId, 1, null))), MEMBER);
    assertThat(created.files()).hasSize(1);
    assertThat(created.files().get(0).duplicate().taskCode()).isEmpty();
    verify(repo).insertVersion(any(), any(), any(), eq(1), eq("TAI_LEN"), eq(LEAD), any());
    verify(lifecycle).saveDraft(eq(TENANT), eq(versionId), eq(0L), any(), eq(MEMBER));

    // Kiểm đầu vào của nháp.
    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("NHAP", 1)));
    List<FileInput> tooMany = Collections.nCopies(21, new FileInput(UUID.randomUUID(), null, null));
    assertThat(code(() -> service.updateDraft(TENANT, evidenceId, 1, 1, draft(tooMany), MEMBER)))
        .isEqualTo("COMMON-422-VALIDATION");
    FileInput same = new FileInput(objectId, null, null);
    assertThat(
            code(
                () ->
                    service.updateDraft(
                        TENANT, evidenceId, 1, 1, draft(List.of(same, same)), MEMBER)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.updateDraft(
                        TENANT,
                        evidenceId,
                        1,
                        1,
                        draft(List.of(new FileInput(objectId, null, "ngắn"))),
                        MEMBER)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThat(
            code(
                () ->
                    service.updateDraft(
                        TENANT,
                        evidenceId,
                        1,
                        1,
                        draft(List.of(new FileInput(UUID.randomUUID(), null, null))),
                        MEMBER)))
        .isEqualTo("PRODUCT-422-NO-OBJECT");
    assertThat(
            code(
                () ->
                    service.updateDraft(
                        TENANT,
                        evidenceId,
                        1,
                        1,
                        draft(List.of(new FileInput(objectId, 2, null))),
                        MEMBER)))
        .isEqualTo("PRODUCT-422-HASH-MISMATCH");
    var saved =
        service.updateDraft(
            TENANT,
            evidenceId,
            1,
            1,
            new DraftInput(
                null,
                " ",
                List.of(new FileInput(objectId, null, "Dùng chung cho hai nhiệm vụ cùng kỳ")),
                true,
                UUID.randomUUID(),
                "12/BC",
                LocalDate.of(2027, 1, 5),
                "Trích yếu",
                "Ghi chú",
                List.of("taskId", "docNumber", "docNumber")),
            MEMBER);
    assertThat(saved.version().id()).isEqualTo(versionId);
    var empty = service.updateDraft(TENANT, evidenceId, 1, 1, draft(null), MEMBER);
    assertThat(empty.version().id()).isEqualTo(versionId);
    assertThatThrownBy(() -> service.getVersion(TENANT, evidenceId, 9))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void nop() {
    when(repo.findEvidence(TENANT, evidenceId)).thenReturn(Optional.of(evidence()));
    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("NHAP", 3)));
    assertThatThrownBy(() -> service.submit(TENANT, evidenceId, 1, 2, true, false, MEMBER))
        .isInstanceOf(VersionConflictException.class);

    when(repo.filesOf(TENANT, 20271, versionId)).thenReturn(List.of());
    assertThat(code(() -> service.submit(TENANT, evidenceId, 1, 3, true, false, MEMBER)))
        .isEqualTo("PRODUCT-422-NO-OBJECT");
    FileRow pending = new FileRow(objectId, 1, 1, sha(1), "CHO_QUET", null, null);
    when(repo.filesOf(TENANT, 20271, versionId)).thenReturn(List.of(new FileView(pending, null)));
    assertThat(code(() -> service.submit(TENANT, evidenceId, 1, 3, true, false, MEMBER)))
        .isEqualTo("PRODUCT-422-HASH-MISMATCH");

    FileRow ok = new FileRow(objectId, 1, 1, sha(1), "SACH", null, null);
    when(repo.filesOf(TENANT, 20271, versionId)).thenReturn(List.of(new FileView(ok, stored(1))));
    EvidenceVersion submitted =
        new EvidenceVersion(
            versionId,
            evidenceId,
            UUID.randomUUID(),
            taskId,
            1,
            EvidenceStatus.DA_NOP,
            objectId,
            sha(1),
            "SACH",
            true,
            null,
            LEAD,
            NOW,
            null,
            null,
            null,
            4);
    when(lifecycle.submitVersion(eq(TENANT), eq(versionId), anyLong(), any(), anyBoolean()))
        .thenReturn(
            new EvidenceLifecycleService.Result(
                submitted,
                4,
                Optional.empty(),
                Optional.of(
                    new TransitionOutcome(Outcome.APPLIED, task("CHO_NGHIEM_THU", LEAD, null)))));
    var done = service.submit(TENANT, evidenceId, 1, 3, true, true, MEMBER);
    assertThat(done.reviewerId()).isEqualTo(ASSIGNER);
    assertThat(done.taskStatusChanged()).isEqualTo("CHO_NGHIEM_THU");
    verify(repo).markAttested(TENANT, 20271, versionId, NOW);
    verify(events).submitted(any());
    verify(events)
        .reviewRequested(
            eq(TENANT), eq(ASSIGNER), eq("NV-1"), eq(evidenceId), eq(1), any(), eq(4L));

    UUID acceptor = UUID.randomUUID();
    when(tasks.find(TENANT, taskId))
        .thenReturn(Optional.of(task("DANG_THUC_HIEN", LEAD, acceptor)));
    when(lifecycle.submitVersion(eq(TENANT), eq(versionId), anyLong(), any(), anyBoolean()))
        .thenReturn(
            new EvidenceLifecycleService.Result(submitted, 4, Optional.empty(), Optional.empty()));
    var noTask = service.submit(TENANT, evidenceId, 1, 3, true, false, MEMBER);
    assertThat(noTask.reviewerId()).isEqualTo(acceptor);
    assertThat(noTask.taskStatusChanged()).isNull();

    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("DA_NOP", 4)));
    assertThat(code(() -> service.submit(TENANT, evidenceId, 1, 4, true, false, MEMBER)))
        .isEqualTo("PRODUCT-409-INVALID-STATE");
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(any(), any());
    assertThatThrownBy(() -> service.submit(TENANT, evidenceId, 1, 4, true, false, MEMBER))
        .isInstanceOf(PeriodLockedException.class);
    assertThatThrownBy(
            () -> service.updateDraft(TENANT, evidenceId, 1, 4, draft(List.of()), MEMBER))
        .isInstanceOf(PeriodLockedException.class);
    when(repo.lockEvidence(TENANT, evidenceId)).thenReturn(Optional.of(evidence()));
    assertThatThrownBy(() -> service.createVersion(TENANT, evidenceId, draft(List.of()), MEMBER))
        .isInstanceOf(PeriodLockedException.class);
  }

  @Test
  void docVaLietKe() {
    when(repo.findEvidence(TENANT, evidenceId)).thenReturn(Optional.of(evidence()));
    when(repo.versionSummaries(TENANT, 20271, evidenceId)).thenReturn(List.of());
    assertThat(service.getEvidence(TENANT, evidenceId).task().code()).isEqualTo("NV-1");
    assertThatThrownBy(() -> service.getEvidence(TENANT, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.getDeliverable(TENANT, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.listDeliverables(TENANT, taskId, null, 21)).isEmpty();
    assertThat(service.listEvidences(TENANT, null, null, 21)).isEmpty();
    assertThat(service.taskOf(TENANT, taskId)).isPresent();
  }

  // ---------------------------------------------------------------- bản chiếu

  @Test
  void banChieuDoiTuong() {
    EvidenceObjectProjector projector =
        new EvidenceObjectProjector(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    DraftRef draft = new DraftRef(versionId, 20271);
    when(repo.upsertStoredObject(any(), any(), any(), any())).thenReturn(true, false);
    when(repo.syncDraftFileScan(TENANT, objectId)).thenReturn(List.of(draft));
    when(repo.filesOf(TENANT, 20271, versionId))
        .thenReturn(
            List.of(new FileView(new FileRow(objectId, 1, 1, sha(1), "NHIEM", null, null), null)));
    projector.onStored(TENANT, stored(1), LEAD);
    projector.onStored(TENANT, stored(1), LEAD);
    verify(repo).writeDraftScan(TENANT, 20271, versionId, "NHIEM");

    when(repo.applyScanVerdict(any(), any(), eq(1), any(), any(), any())).thenReturn(false);
    projector.onScanned(TENANT, objectId, 1, "BLA", LEAD);
    verify(repo).applyScanVerdict(TENANT, objectId, 1, "LOI", LEAD, NOW);
    when(repo.applyScanVerdict(any(), any(), eq(2), any(), any(), any())).thenReturn(true);
    projector.onScanned(TENANT, objectId, 2, "CLEAN", LEAD);
    verify(repo).applyScanVerdict(TENANT, objectId, 2, "SACH", LEAD, NOW);

    assertThat(stored(1).stored()).isTrue();
    assertThat(new StoredObject(objectId, 1, null, null, null, false, "SACH", null).stored())
        .isFalse();
    assertThat(new StoredObject(objectId, 1, sha(1), null, null, false, "SACH", null).stored())
        .isFalse();
    verify(repo, never()).writeDraftScan(any(), eq(0), any(), any());
  }
}
