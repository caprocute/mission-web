package vn.viettel.okrkpi.task.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.document.IncomingDocService.Confirm;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Extraction;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Fields;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Suggestion;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocContents;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Claim;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Content;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.DocRow;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Link;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Linked;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Paragraph;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Received;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.TaskRef;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.application.similar.SimilarTaskService;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec.Assignee;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class IncomingDocServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID DOC = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID OTHER_UNIT = UUID.randomUUID();
  static final UUID ME = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID TASK = UUID.randomUUID();
  static final UUID SUGGESTION = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  static final TaskActor MANAGER = new TaskActor(ME, Set.of("manager"));

  private final IncomingDocs docs = mock(IncomingDocs.class);
  private final IncomingDocContents contents = mock(IncomingDocContents.class);
  private final DocExtractions extractions = mock(DocExtractions.class);
  private final TaskDraftService drafts = mock(TaskDraftService.class);
  private final TaskAssignService assign = mock(TaskAssignService.class);
  private final SimilarTaskService similar = mock(SimilarTaskService.class);
  private final TaskReminderNotices notices = mock(TaskReminderNotices.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private IncomingDocService service;

  @BeforeEach
  void setUp() {
    service = service(contents, extractions, similar);
    when(org.assignmentsOf(eq(TENANT), eq(ME), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(), ME, UNIT, null, null, "CHINH", "CHINH", false, null, null)));
    when(docs.find(TENANT, DOC)).thenReturn(Optional.of(doc("CHO_XU_LY", "C1")));
    when(docs.claims(TENANT, DOC)).thenReturn(List.of());
    when(periods.findCurrent(TENANT))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "KY",
                    "Kỳ",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 12, 31),
                    PeriodStatus.MO)));
    when(drafts.insertDraft(eq(TENANT), any(), eq(MANAGER), eq(UNIT)))
        .thenReturn(
            new DraftRow(TASK, "NV-2026-0001", "NHAP", PERIOD, 20264, UNIT, ME, false, null, 1));
    when(docs.claim(
            eq(TENANT), eq(DOC), anyInt(), any(), anyInt(), anyString(), anyString(), eq(ME)))
        .thenReturn(Optional.empty());
    when(similar.similarTo(TENANT, TASK, "auth"))
        .thenReturn(new SimilarTaskService.Result(List.of(), true));
  }

  private IncomingDocService service(
      IncomingDocContents c, DocExtractions e, SimilarTaskService s) {
    return new IncomingDocService(
        docs,
        c,
        e,
        drafts,
        assign,
        s,
        notices,
        periods,
        org,
        id -> List.of(LEAD, ME),
        TransactionOperations.withoutTransaction(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  static DocRow doc(String status, String classification) {
    return new DocRow(
        DOC,
        UNIT,
        "VOFFICE",
        "VOFFICE",
        "ext-1",
        "123/UBND-VP",
        LocalDate.of(2026, 10, 20),
        "UBND tỉnh",
        "Về việc triển khai",
        "EdXML",
        "UBND TỈNH",
        "Về việc triển khai",
        "Căn cứ",
        List.of(new Paragraph(1, "Mở đầu"), new Paragraph(2, "Giao Sở A chủ trì")),
        "Sở A",
        classification,
        true,
        1,
        UUID.randomUUID(),
        NOW,
        status,
        null,
        1);
  }

  static Fields fields() {
    return new Fields(
        "Triển khai kế hoạch",
        UNIT,
        "Sở A",
        List.of(new Assignee("UNIT", OTHER_UNIT)),
        List.of("Sở B"),
        LocalDate.of(2026, 12, 1),
        "Báo cáo",
        null,
        null,
        null,
        null);
  }

  static Suggestion suggestion(String decision) {
    return new Suggestion(
        SUGGESTION, 2, new BigDecimal("0.91"), decision, fields(), fields(), List.of(), null);
  }

  static Confirm confirm(String reason, List<String> edited) {
    return new Confirm(DOC, SUGGESTION, 2, fields(), edited, reason);
  }

  // ───────── Nhận ─────────

  @Test
  void receiveStoresMetadataThenContent() {
    Received r =
        new Received(
            DOC, "VOFFICE", "VOFFICE", "e", "1", LocalDate.now(), "X", "Y", "k", 1, null, NOW);
    Content c = new Content(UNIT, "EdXML", "H", "T", "P", List.of(), "R", "C1");
    when(contents.fetch(TENANT, DOC)).thenReturn(Optional.of(c));

    service.onReceived(TENANT, r);

    verify(docs).upsertReceived(TENANT, r);
    verify(docs).storeContent(TENANT, DOC, c);
  }

  @Test
  void receiveWithoutContentPortKeepsMetadataOnly() {
    Received r =
        new Received(DOC, "EDXML", "X", "e", "1", LocalDate.now(), "X", "Y", "k", 1, null, NOW);
    service(null, null, null).onReceived(TENANT, r);
    verify(docs).upsertReceived(TENANT, r);
    verify(docs, never()).storeContent(any(), any(), any());
  }

  // ───────── Xem ─────────

  @Test
  void listCountsPendingSuggestionsNotYetClaimedAndPages() {
    DocRow d = doc("CHO_XU_LY", "C1");
    DocRow d2 = doc("CHO_XU_LY", "C3");
    when(docs.list(TENANT, UNIT, null, 2, null)).thenReturn(List.of(d, d2));
    when(extractions.find(TENANT, DOC, "auth"))
        .thenReturn(
            Optional.of(
                new Extraction(
                    "XONG",
                    List.of(
                        suggestion("CHO_XAC_NHAN"),
                        new Suggestion(
                            UUID.randomUUID(),
                            1,
                            BigDecimal.ONE,
                            "CHO_XAC_NHAN",
                            fields(),
                            fields(),
                            List.of(),
                            null)))));
    when(docs.claims(TENANT, DOC))
        .thenReturn(List.of(new Claim(2, TASK, 20264, "NV-1", "TAO_MOI")));

    IncomingDocService.Page page = service.list(TENANT, MANAGER, null, 1, null, "auth");

    assertThat(page.items()).hasSize(1);
    assertThat(page.next()).isNotNull();
    IncomingDocService.DocView v = page.items().get(0);
    assertThat(v.extractionStatus()).isEqualTo("XONG");
    assertThat(v.suggestionCount()).isEqualTo(2);
    assertThat(v.pendingCount()).isEqualTo(1);
    assertThat(v.assignmentParagraphs()).containsExactlyInAnyOrder(1, 2);
  }

  @Test
  void listForPersonWithoutUnitIsEmpty() {
    TaskActor nobody = new TaskActor(UUID.randomUUID(), Set.of("manager"));
    assertThat(service.list(TENANT, nobody, null, 10, null, null).items()).isEmpty();
  }

  @Test
  void getTreatsAiOutageAsExtracting() {
    when(extractions.find(TENANT, DOC, "auth"))
        .thenThrow(new DocExtractions.Unavailable("x", null));
    IncomingDocService.DocView v = service.get(TENANT, MANAGER, DOC, "auth");
    assertThat(v.extractionStatus()).isEqualTo("DANG_TRICH");
    assertThat(v.pendingCount()).isZero();
    assertThat(v.assignmentParagraphs()).isEmpty();
    assertThat(service(contents, null, similar).get(TENANT, MANAGER, DOC, "a").extraction())
        .isNull();
  }

  @Test
  void restrictedDocumentIsNeverExtracted() {
    when(docs.find(TENANT, DOC)).thenReturn(Optional.of(doc("CHO_XU_LY", "C2")));
    assertThat(service.get(TENANT, MANAGER, DOC, "a").extractionStatus())
        .isEqualTo("KHONG_TRICH_DUOC");
    verify(extractions, never()).find(any(), any(), any());
  }

  @Test
  void otherUnitGets404() {
    when(org.assignmentsOf(eq(TENANT), eq(ME), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    ME,
                    OTHER_UNIT,
                    null,
                    null,
                    "CHINH",
                    "CHINH",
                    false,
                    null,
                    null)));
    assertThatThrownBy(() -> service.get(TENANT, MANAGER, DOC, "a"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void aiReaderSkipsUnitCheckButOnlyForC0C1() {
    TaskActor ai = new TaskActor(UUID.randomUUID(), Set.of("ai_readonly"));
    assertThat(service.get(TENANT, ai, DOC, "a").extraction()).isNull();
    verify(extractions, never()).find(any(), any(), any());

    when(docs.find(TENANT, DOC)).thenReturn(Optional.of(doc("CHO_XU_LY", "C3")));
    assertThatThrownBy(() -> service.get(TENANT, ai, DOC, "a"))
        .isInstanceOf(NoSuchElementException.class);
  }

  // ───────── Bỏ qua ─────────

  @Test
  void dismissNeedsReasonAndPendingDoc() {
    assertThatThrownBy(() -> service.dismiss(TENANT, MANAGER, DOC, "  ", null))
        .isInstanceOf(BusinessException.class)
        .extracting(Throwable::getMessage)
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.dismiss(TENANT, MANAGER, DOC, "x".repeat(501), null))
        .isInstanceOf(BusinessException.class);

    when(docs.dismiss(TENANT, DOC, "Không liên quan", ME, NOW)).thenReturn(false);
    assertThatThrownBy(() -> service.dismiss(TENANT, MANAGER, DOC, " Không liên quan ", null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-INVALID-STATE");

    when(docs.dismiss(TENANT, DOC, "Không liên quan", ME, NOW)).thenReturn(true);
    assertThat(service.dismiss(TENANT, MANAGER, DOC, "Không liên quan", null).doc().id())
        .isEqualTo(DOC);
  }

  // ───────── Xác nhận ─────────

  @Test
  void confirmCreatesDraftClaimsLinksAndReportsDecision() {
    when(extractions.find(TENANT, DOC, "auth"))
        .thenReturn(Optional.of(new Extraction("XONG", List.of(suggestion("CHO_XAC_NHAN")))));

    IncomingDocService.Outcome out =
        service.createFromDocument(
            TENANT, MANAGER, confirm(null, List.of("dueDate", "dueDate")), "auth", "idem");

    ArgumentCaptor<TaskDraftSpec> spec = ArgumentCaptor.forClass(TaskDraftSpec.class);
    verify(drafts).insertDraft(eq(TENANT), spec.capture(), eq(MANAGER), eq(UNIT));
    assertThat(spec.getValue().source()).isEqualTo("VAN_BAN");
    assertThat(spec.getValue().basis())
        .isEqualTo("Văn bản 123/UBND-VP ngày 20/10/2026 của UBND tỉnh");
    ArgumentCaptor<Link> link = ArgumentCaptor.forClass(Link.class);
    verify(docs).link(eq(TENANT), link.capture(), eq(ME));
    assertThat(link.getValue().edited()).containsExactly("dueDate");
    assertThat(link.getValue().kept()).doesNotContain("dueDate").contains("title");
    verify(docs).markProcessed(TENANT, DOC, ME, NOW);
    verify(extractions)
        .accept(TENANT, SUGGESTION, true, List.of("dueDate"), "task.task:" + TASK, "auth", "idem");
    assertThat(out.decision()).isEqualTo("DA_XAC_NHAN");
    assertThat(out.taskCode()).isEqualTo("NV-2026-0001");
    assertThat(out.suggestion().id()).isEqualTo(SUGGESTION);
  }

  @Test
  void confirmSurvivesAiOutageWhenRecordingDecision() {
    doThrow(new DocExtractions.Unavailable("down", null))
        .when(extractions)
        .accept(any(), any(), eq(false), any(), any(), any(), any());
    when(extractions.find(TENANT, DOC, "auth"))
        .thenThrow(new DocExtractions.Unavailable("x", null));

    IncomingDocService.Outcome out =
        service.createFromDocument(TENANT, MANAGER, confirm(null, null), "auth", null);

    assertThat(out.taskId()).isEqualTo(TASK);
    assertThat(out.suggestion()).isNull();
  }

  @Test
  void duplicateParagraphIs409() {
    when(docs.claim(eq(TENANT), eq(DOC), eq(2), any(), anyInt(), anyString(), anyString(), eq(ME)))
        .thenReturn(Optional.of(new Claim(2, UUID.randomUUID(), 20264, "NV-2026-0099", "TAO_MOI")));

    assertThatThrownBy(
            () -> service.createFromDocument(TENANT, MANAGER, confirm(null, null), "auth", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("NV-2026-0099"))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-DUPLICATE-PARAGRAPH");
    verify(docs, never()).link(any(), any(), any());
  }

  @Test
  void similarTaskRequiresReason() {
    SimilarTaskService.Match m = new SimilarTaskService.Match(null, new BigDecimal("0.9"));
    when(similar.similarTo(TENANT, TASK, "auth"))
        .thenReturn(new SimilarTaskService.Result(List.of(m), true));

    assertThatThrownBy(
            () -> service.createFromDocument(TENANT, MANAGER, confirm(" ", null), "auth", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("gần giống"));
    assertThatThrownBy(
            () ->
                service.createFromDocument(
                    TENANT, MANAGER, confirm("x".repeat(1001), null), "auth", null))
        .isInstanceOf(BusinessException.class);

    IncomingDocService.Outcome out =
        service.createFromDocument(TENANT, MANAGER, confirm("Khác phạm vi", null), "auth", null);
    assertThat(out.similar()).hasSize(1);
  }

  @Test
  void confirmValidatesFieldsParagraphStateAndPeriod() {
    Fields bad =
        new Fields(
            " ",
            null,
            null,
            List.of(new Assignee("X", UNIT)),
            List.of(),
            null,
            " ",
            null,
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                service.createFromDocument(
                    TENANT,
                    MANAGER,
                    new Confirm(DOC, null, 2, bad, List.of("nope"), null),
                    null,
                    null))
        .isInstanceOf(BusinessException.class)
        .extracting(Throwable::getMessage)
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.createFromDocument(
                    TENANT, MANAGER, new Confirm(DOC, null, 2, null, null, null), null, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.createFromDocument(
                    TENANT, MANAGER, new Confirm(DOC, null, 9, fields(), null, null), null, null))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).detail()).contains("đoạn 9"));

    when(periods.findCurrent(TENANT)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.createFromDocument(TENANT, MANAGER, confirm(null, null), null, null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-PERIOD-LOCKED");

    when(docs.find(TENANT, DOC)).thenReturn(Optional.of(doc("DA_BO_QUA", "C1")));
    assertThatThrownBy(
            () -> service.createFromDocument(TENANT, MANAGER, confirm(null, null), null, null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-INVALID-STATE");
  }

  @Test
  void confirmInLockedPeriodIs409() {
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(
            () -> service.createFromDocument(TENANT, MANAGER, confirm(null, null), null, null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-PERIOD-LOCKED");
  }

  // ───────── Gộp ─────────

  static TaskRef ref(String status, String source, UUID unit) {
    return new TaskRef(TASK, PERIOD, 20264, "NV-2026-0002", "Nhiệm vụ cũ", status, source, unit, 4);
  }

  @Test
  void mergeLinksClaimsAndNotifiesLeadExceptActor() {
    when(docs.task(TENANT, TASK)).thenReturn(Optional.of(ref("DANG_THUC_HIEN", "KE_HOACH", UNIT)));

    IncomingDocService.Outcome out =
        service.mergeIntoTask(TENANT, MANAGER, TASK, DOC, SUGGESTION, 2, "auth", null);

    verify(docs).claim(TENANT, DOC, 2, TASK, 20264, "NV-2026-0002", "GOP", ME);
    ArgumentCaptor<TaskReminderNotices.Notice> n =
        ArgumentCaptor.forClass(TaskReminderNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(LEAD);
    assertThat(n.getValue().template()).isEqualTo("VAN_BAN_GOP_VAO_NHIEM_VU");
    verify(extractions)
        .accept(
            TENANT,
            SUGGESTION,
            false,
            List.of(),
            "task.task:" + TASK,
            "auth",
            "task-doc:" + SUGGESTION);
    assertThat(out.decision()).isEqualTo("DA_GOP");
  }

  @Test
  void mergeRejectsClosedOtherUnitLockedAndDuplicate() {
    when(docs.task(TENANT, TASK)).thenReturn(Optional.of(ref("HOAN_THANH", "KE_HOACH", UNIT)));
    assertThatThrownBy(() -> service.mergeIntoTask(TENANT, MANAGER, TASK, DOC, null, 2, null, null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-INVALID-STATE");

    when(docs.task(TENANT, TASK)).thenReturn(Optional.of(ref("NHAP", "KE_HOACH", OTHER_UNIT)));
    assertThatThrownBy(() -> service.mergeIntoTask(TENANT, MANAGER, TASK, DOC, null, 2, null, null))
        .isInstanceOf(NoSuchElementException.class);

    when(docs.task(TENANT, TASK)).thenReturn(Optional.of(ref("NHAP", "KE_HOACH", UNIT)));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> service.mergeIntoTask(TENANT, MANAGER, TASK, DOC, null, 2, null, null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-PERIOD-LOCKED");
  }

  @Test
  void mergeDuplicateParagraphIs409() {
    when(docs.task(TENANT, TASK)).thenReturn(Optional.of(ref("NHAP", "KE_HOACH", UNIT)));
    when(docs.claim(eq(TENANT), eq(DOC), eq(2), any(), anyInt(), anyString(), anyString(), eq(ME)))
        .thenReturn(Optional.of(new Claim(2, UUID.randomUUID(), 20264, "NV-9", "GOP")));
    assertThatThrownBy(() -> service.mergeIntoTask(TENANT, MANAGER, TASK, DOC, null, 2, null, null))
        .extracting(Throwable::getMessage)
        .isEqualTo("TASK-409-DUPLICATE-PARAGRAPH");
  }

  // ───────── Giao theo lô ─────────

  @Test
  void batchAssignsConfirmedAndBlocksUnconfirmed() {
    UUID unconfirmed = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    UUID assigned = UUID.randomUUID();
    UUID failing = UUID.randomUUID();
    when(docs.task(TENANT, TASK)).thenReturn(Optional.of(ref("NHAP", "VAN_BAN", UNIT)));
    when(docs.linkOf(TENANT, 20264, TASK))
        .thenReturn(Optional.of(new Linked(DOC, UNIT, 2, true, "Lý do")));
    TaskRow row = mock(TaskRow.class);
    when(row.status()).thenReturn(TaskStatus.DA_GIAO);
    when(assign.assignTask(TENANT, TASK, 4, "Lý do", "auth", MANAGER))
        .thenReturn(new TaskAssignService.Assigned(row, LEAD));
    when(docs.task(TENANT, unconfirmed))
        .thenReturn(
            Optional.of(
                new TaskRef(unconfirmed, PERIOD, 20264, "NV-3", "t", "NHAP", "KE_HOACH", UNIT, 1)));
    when(docs.task(TENANT, assigned))
        .thenReturn(
            Optional.of(
                new TaskRef(assigned, PERIOD, 20264, "NV-4", "t", "DA_GIAO", "VAN_BAN", UNIT, 1)));
    when(docs.linkOf(TENANT, 20264, assigned))
        .thenReturn(Optional.of(new Linked(DOC, UNIT, 1, true, null)));
    when(docs.task(TENANT, failing))
        .thenReturn(
            Optional.of(
                new TaskRef(failing, PERIOD, 20264, "NV-5", "t", "NHAP", "VAN_BAN", UNIT, 1)));
    when(docs.linkOf(TENANT, 20264, failing))
        .thenReturn(Optional.of(new Linked(DOC, UNIT, 3, true, null)));
    when(assign.assignTask(TENANT, failing, 1, null, "auth", MANAGER))
        .thenThrow(new BusinessException("TASK-409-INVALID-STATE", "Lỗi giao"));

    List<IncomingDocService.BatchItem> out =
        service.batchAssign(
            TENANT,
            MANAGER,
            java.util.Arrays.asList(TASK, TASK, null, unconfirmed, missing, assigned, failing),
            "auth");

    assertThat(out).hasSize(5);
    assertThat(out.get(0).assigned()).isTrue();
    assertThat(out.get(0).status()).isEqualTo("DA_GIAO");
    assertThat(out.get(1).message())
        .isEqualTo("Nhiệm vụ NV-3 chưa được xác nhận nên chưa giao được.");
    assertThat(out.get(2).message()).isEqualTo("Không tìm thấy nhiệm vụ.");
    assertThat(out.get(3).message()).contains("đã giao");
    assertThat(out.get(4).message()).isEqualTo("Lỗi giao");
  }

  @Test
  void batchReasonNamesFirstBlockingRule() {
    assertThat(
            IncomingDocService.reasonOf(
                new BusinessException(
                    "COMMON-422-VALIDATION",
                    "Nhiệm vụ chưa đủ điều kiện để giao",
                    List.of(
                        vn.viettel.okrkpi.shared.error.ProblemFieldError.of(
                            "leadPersonId", "Thiếu người chủ trì")))))
        .isEqualTo("Nhiệm vụ chưa đủ điều kiện để giao: Thiếu người chủ trì");
    assertThat(IncomingDocService.reasonOf(new BusinessException("TASK-409-INVALID-STATE")))
        .isEqualTo("TASK-409-INVALID-STATE");
  }

  @Test
  void systemHelpers() {
    assertThat(IncomingDocService.systemActor()).isEqualTo(new UUID(0, 0));
    assertThat(service.now()).isEqualTo(NOW);
  }
}
