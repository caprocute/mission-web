package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.DraftInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.EvidenceDetail;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.EvidenceInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.FileInput;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DraftDetails;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments.Doc;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments.Page;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments.Transferred;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** CORE-03-04 · BR-07-13: văn bản đã ban hành làm minh chứng. */
class EvidenceExternalDocTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID LEAD = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final EvidenceActor MEMBER = new EvidenceActor(LEAD, Set.of("member"));
  static final Instant NOW = Instant.parse("2027-02-01T08:00:00Z");
  static final String REF = "47-BC/BTCĐUK";

  private EvidenceSubmissionRepository repo;
  private EvidenceLifecycleService lifecycle;
  private EvidenceEvents events;
  private EvidenceSubmissionService service;

  private final UUID taskId = UUID.randomUUID();
  private final UUID evidenceId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID original = UUID.randomUUID();
  private final UUID appendix = UUID.randomUUID();
  private final Doc doc =
      new Doc(REF, REF, LocalDate.of(2027, 1, 19), "Báo cáo chuyên đề", "Trần Thị Bình", true);

  @BeforeEach
  void setUp() {
    repo = mock(EvidenceSubmissionRepository.class);
    lifecycle = mock(EvidenceLifecycleService.class);
    events = mock(EvidenceEvents.class);
    TaskApi tasks = mock(TaskApi.class);
    service =
        new EvidenceSubmissionService(
            repo,
            lifecycle,
            mock(PeriodApi.class),
            tasks,
            events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(tasks.find(TENANT, taskId))
        .thenReturn(
            Optional.of(
                new TaskSummary(
                    taskId,
                    PERIOD,
                    20271,
                    "NV-1",
                    "Nhiệm vụ",
                    "DANG_THUC_HIEN",
                    false,
                    false,
                    UUID.randomUUID(),
                    null,
                    LEAD,
                    LocalDate.of(2027, 3, 31),
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    1)));
    EvidenceView e = evidence("VAN_BAN", REF, null);
    when(repo.findEvidence(TENANT, evidenceId)).thenReturn(Optional.of(e));
    when(repo.lockEvidence(TENANT, evidenceId)).thenReturn(Optional.of(e));
    when(repo.latestVersion(TENANT, 20271, evidenceId)).thenReturn(Optional.empty());
    when(repo.storedObjects(any(), any()))
        .thenReturn(
            Map.of(
                original, stored(original, 4096L),
                appendix, stored(appendix, 100L)));
    when(repo.firstDuplicate(any(), any(), any())).thenReturn(Optional.empty());
  }

  private EvidenceView evidence(String sourceType, String ref, Integer current) {
    return new EvidenceView(
        evidenceId,
        PERIOD,
        20271,
        UUID.randomUUID(),
        taskId,
        "Báo cáo chuyên đề",
        sourceType,
        ref,
        current,
        null,
        false,
        null,
        null,
        1);
  }

  private static StoredObject stored(UUID id, long size) {
    return new StoredObject(id, 1, sha(9), size, "application/pdf", false, "CHO_QUET", NOW);
  }

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
  }

  private VersionView version(String source, String sourceDocRef) {
    return new VersionView(
        versionId,
        evidenceId,
        taskId,
        PERIOD,
        20271,
        1,
        "NHAP",
        source,
        sourceDocRef,
        "CHO_QUET",
        false,
        false,
        false,
        null,
        null,
        REF,
        doc.docDate(),
        doc.summary(),
        null,
        null,
        null,
        null,
        null,
        null,
        2);
  }

  private Transferred transferred(boolean valid) {
    return new Transferred(original, 1, sha(9), 4096L, "application/pdf", valid, doc);
  }

  private static DraftInput draft(String source, String ref, List<UUID> files, boolean ai) {
    return new DraftInput(
        source,
        ref,
        files.stream().map(f -> new FileInput(f, null, null)).toList(),
        ai,
        ai ? UUID.randomUUID() : null,
        null,
        null,
        null,
        "Ghi chú",
        List.of());
  }

  private static BusinessException business(Runnable work) {
    return (BusinessException) catchThrowable(work::run);
  }

  // ------------------------------------------------------------ dịch vụ nộp

  @Test
  void moPhienBanTuVanBanGiuTepGocVaGhiSoCai() {
    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("VAN_BAN_HE_THONG", REF)));

    service.openExternalDocVersion(
        TENANT,
        evidenceId,
        draft("TAI_LEN", "khac", List.of(appendix, original), true),
        transferred(true),
        MEMBER);

    ArgumentCaptor<StoredObject> object = ArgumentCaptor.forClass(StoredObject.class);
    verify(repo).upsertStoredObject(eq(TENANT), object.capture(), eq(LEAD), eq(NOW));
    assertThat(object.getValue().id()).isEqualTo(original);
    assertThat(object.getValue().scanStatus()).isEqualTo("CHO_QUET");
    verify(repo)
        .insertVersion(eq(TENANT), any(), any(), eq(1), eq("VAN_BAN_HE_THONG"), eq(LEAD), eq(NOW));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<FileRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(repo).replaceFiles(eq(TENANT), any(), rows.capture(), eq(LEAD), eq(NOW));
    assertThat(rows.getValue()).extracting(FileRow::objectId).containsExactly(original, appendix);
    ArgumentCaptor<DraftDetails> details = ArgumentCaptor.forClass(DraftDetails.class);
    verify(repo).writeDraftDetails(eq(TENANT), eq(20271), eq(versionId), details.capture());
    assertThat(details.getValue().source()).isEqualTo("VAN_BAN_HE_THONG");
    assertThat(details.getValue().sourceDocRef()).isEqualTo(REF);
    assertThat(details.getValue().docNumber()).isEqualTo(REF);
    assertThat(details.getValue().docDate()).isEqualTo(doc.docDate());
    assertThat(details.getValue().docSummary()).isEqualTo(doc.summary());
    assertThat(details.getValue().aiSuggestionId()).isNull();
    ArgumentCaptor<EvidenceEvents.ExternalDocVerified> verified =
        ArgumentCaptor.forClass(EvidenceEvents.ExternalDocVerified.class);
    verify(events).externalDocVerified(verified.capture());
    assertThat(verified.getValue().ref()).isEqualTo(REF);
    assertThat(verified.getValue().objectId()).isEqualTo(original);
    assertThat(verified.getValue().versionId()).isEqualTo(versionId);
  }

  @Test
  void chuKySaiKhongMoPhienBan() {
    BusinessException e =
        business(
            () ->
                service.openExternalDocVersion(
                    TENANT,
                    evidenceId,
                    draft(null, null, List.of(), false),
                    transferred(false),
                    MEMBER));
    assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(e.errors().get(0).rule()).isEqualTo("BR-07-13");
    verify(repo, never()).upsertStoredObject(any(), any(), any(), any());
    verify(repo, never()).insertVersion(any(), any(), any(), anyInt(), any(), any(), any());
  }

  @Test
  void nhapTuVanBanKhongDoiTepGocVaNguon() {
    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("VAN_BAN_HE_THONG", REF)));
    when(repo.filesOf(TENANT, 20271, versionId))
        .thenReturn(
            List.of(
                new FileView(
                    new FileRow(original, 1, 1, sha(9), "CHO_QUET", null, null),
                    stored(original, 4096L))));

    BusinessException swapped =
        business(
            () ->
                service.updateDraft(
                    TENANT,
                    evidenceId,
                    1,
                    2,
                    draft("VAN_BAN_HE_THONG", null, List.of(appendix), false),
                    MEMBER));
    assertThat(swapped.errors().get(0).field()).isEqualTo("files[0].objectId");
    assertThat(swapped.errors().get(0).rule()).isEqualTo("BR-07-13");

    BusinessException empty =
        business(
            () ->
                service.updateDraft(
                    TENANT,
                    evidenceId,
                    1,
                    2,
                    draft("VAN_BAN_HE_THONG", null, List.of(), false),
                    MEMBER));
    assertThat(empty.errors().get(0).field()).isEqualTo("files[0].objectId");

    BusinessException otherRef =
        business(
            () ->
                service.updateDraft(
                    TENANT,
                    evidenceId,
                    1,
                    2,
                    draft("VAN_BAN_HE_THONG", "99-BC", List.of(original), false),
                    MEMBER));
    assertThat(otherRef.errors().get(0).field()).isEqualTo("sourceDocRef");

    BusinessException otherSource =
        business(
            () ->
                service.updateDraft(
                    TENANT,
                    evidenceId,
                    1,
                    2,
                    draft("TAI_LEN", null, List.of(original), false),
                    MEMBER));
    assertThat(otherSource.errors().get(0).field()).isEqualTo("sourceDocRef");

    BusinessException ai =
        business(
            () ->
                service.updateDraft(
                    TENANT, evidenceId, 1, 2, draft(null, REF, List.of(original), true), MEMBER));
    assertThat(ai.errors().get(0).field()).isEqualTo("aiPrefill");
    verify(lifecycle, never()).saveDraft(any(), any(), anyLong(), any(), any());

    service.updateDraft(
        TENANT, evidenceId, 1, 2, draft(null, null, List.of(original, appendix), false), MEMBER);
    ArgumentCaptor<DraftDetails> details = ArgumentCaptor.forClass(DraftDetails.class);
    verify(repo).writeDraftDetails(eq(TENANT), eq(20271), eq(versionId), details.capture());
    assertThat(details.getValue().source()).isEqualTo("VAN_BAN_HE_THONG");
    assertThat(details.getValue().sourceDocRef()).isEqualTo(REF);
  }

  @Test
  void nhapThuongKhongTuGanNguonVanBan() {
    BusinessException created =
        business(
            () ->
                service.createVersion(
                    TENANT,
                    evidenceId,
                    draft("VAN_BAN_HE_THONG", REF, List.of(original), false),
                    MEMBER));
    assertThat(created.errors().get(0).field()).isEqualTo("source");

    when(repo.findVersion(TENANT, 20271, evidenceId, 1))
        .thenReturn(Optional.of(version("TAI_LEN", null)));
    BusinessException updated =
        business(
            () ->
                service.updateDraft(
                    TENANT,
                    evidenceId,
                    1,
                    2,
                    draft("VAN_BAN_HE_THONG", REF, List.of(original), false),
                    MEMBER));
    assertThat(updated.errors().get(0).field()).isEqualTo("source");
  }

  // ------------------------------------------------------------ điều phối

  private EvidenceSubmissionService submissions;
  private IssuedDocuments documents;
  private AuditLogger audit;

  private EvidenceExternalDocService orchestrator(boolean hasUnit, boolean configured) {
    submissions = mock(EvidenceSubmissionService.class);
    documents = mock(IssuedDocuments.class);
    audit = mock(AuditLogger.class);
    return new EvidenceExternalDocService(
        submissions,
        configured ? documents : null,
        (t, p, on) -> hasUnit ? Optional.of(UNIT) : Optional.empty(),
        audit,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void timVanBanTheoDonViNguoiGoi() {
    assertThat(new Page(null, null).items()).isEmpty();
    EvidenceExternalDocService none = orchestrator(false, true);
    assertThat(none.search(TENANT, "47", 50, null, MEMBER).items()).isEmpty();
    verify(documents, never()).search(any(), any(), any(), anyInt(), any());

    EvidenceExternalDocService s = orchestrator(true, true);
    when(documents.search(TENANT, UNIT, "47", 200, null)).thenReturn(new Page(List.of(doc), "c"));
    Page page = s.search(TENANT, " 47 ", 500, " ", MEMBER);
    assertThat(page.items()).containsExactly(doc);
    assertThat(page.nextCursor()).isEqualTo("c");

    EvidenceExternalDocService off = orchestrator(true, false);
    assertThatThrownBy(() -> off.search(TENANT, null, 10, null, MEMBER))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  void nhanVanBanCoChuKyHopLeVaTuChoiChuKySai() {
    EvidenceExternalDocService s = orchestrator(true, true);
    Doc bad = new Doc("12-BC", "12-BC", doc.docDate(), "Sai chữ ký", "Nguyễn Văn An", false);
    when(documents.search(eq(TENANT), eq(UNIT), any(), anyInt(), any()))
        .thenReturn(new Page(List.of(doc, bad), null));
    UUID deliverable = UUID.randomUUID();

    EvidenceInput badInput = new EvidenceInput(deliverable, "Báo cáo", "VAN_BAN", "12-BC");
    BusinessException e = business(() -> s.newEvidence(TENANT, badInput, MEMBER));
    assertThat(e.errors().get(0).rule()).isEqualTo("BR-07-13");
    verify(audit).record(any());
    verify(submissions, never()).createEvidence(any(), any(), any());

    EvidenceInput missing = new EvidenceInput(deliverable, "Báo cáo", "VAN_BAN", "khong-co");
    assertThat(business(() -> s.newEvidence(TENANT, missing, MEMBER)).errors().get(0).field())
        .isEqualTo("externalDocRef");
    EvidenceInput blank = new EvidenceInput(deliverable, "Báo cáo", "VAN_BAN", " ");
    assertThat(business(() -> s.newEvidence(TENANT, blank, MEMBER)).errors().get(0).field())
        .isEqualTo("externalDocRef");

    EvidenceInput good = new EvidenceInput(deliverable, "Báo cáo", "VAN_BAN", REF);
    s.newEvidence(TENANT, good, MEMBER);
    verify(submissions).createEvidence(TENANT, good, MEMBER);

    EvidenceInput upload = new EvidenceInput(deliverable, "Báo cáo", "TAI_LEN", null);
    s.newEvidence(TENANT, upload, MEMBER);
    verify(submissions).createEvidence(TENANT, upload, MEMBER);

    EvidenceExternalDocService noUnit = orchestrator(false, true);
    assertThat(business(() -> noUnit.newEvidence(TENANT, good, MEMBER)).errors().get(0).field())
        .isEqualTo("externalDocRef");
  }

  @Test
  void phienBanMoiChuyenTepGocQuaSvc13() {
    EvidenceExternalDocService s = orchestrator(true, true);
    DraftInput input = draft(null, null, List.of(), false);
    when(submissions.getEvidence(TENANT, evidenceId))
        .thenReturn(new EvidenceDetail(evidence("VAN_BAN", REF, 1), null, List.of()));
    String key = "chuyen-van-ban-" + evidenceId + "-2";
    when(documents.transfer(TENANT, UNIT, REF, evidenceId, LEAD, key))
        .thenReturn(Optional.of(transferred(true)));

    s.newVersion(TENANT, evidenceId, input, MEMBER);
    verify(submissions)
        .openExternalDocVersion(eq(TENANT), eq(evidenceId), eq(input), any(), eq(MEMBER));

    when(documents.transfer(TENANT, UNIT, REF, evidenceId, LEAD, key))
        .thenReturn(Optional.of(transferred(false)));
    assertThat(
            business(() -> s.newVersion(TENANT, evidenceId, input, MEMBER)).errors().get(0).rule())
        .isEqualTo("BR-07-13");
    verify(audit).record(any());

    when(documents.transfer(TENANT, UNIT, REF, evidenceId, LEAD, key)).thenReturn(Optional.empty());
    assertThat(
            business(() -> s.newVersion(TENANT, evidenceId, input, MEMBER)).errors().get(0).field())
        .isEqualTo("externalDocRef");

    when(documents.transfer(TENANT, UNIT, REF, evidenceId, LEAD, key))
        .thenThrow(
            new DependencyUnavailableException(
                "integration-gateway",
                DependencyUnavailableException.Reason.TIMEOUT,
                java.time.Duration.ofSeconds(5),
                null));
    assertThatThrownBy(() -> s.newVersion(TENANT, evidenceId, input, MEMBER))
        .isInstanceOf(DependencyUnavailableException.class);

    when(submissions.getEvidence(TENANT, evidenceId))
        .thenReturn(new EvidenceDetail(evidence("VAN_BAN", null, null), null, List.of()));
    assertThat(
            business(() -> s.newVersion(TENANT, evidenceId, input, MEMBER)).errors().get(0).field())
        .isEqualTo("externalDocRef");

    when(submissions.getEvidence(TENANT, evidenceId))
        .thenReturn(new EvidenceDetail(evidence("TAI_LEN", null, null), null, List.of()));
    s.newVersion(TENANT, evidenceId, input, MEMBER);
    verify(submissions).createVersion(TENANT, evidenceId, input, MEMBER);
  }
}
