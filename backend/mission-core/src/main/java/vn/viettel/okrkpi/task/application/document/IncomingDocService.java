package vn.viettel.okrkpi.task.application.document;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Extraction;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Fields;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Suggestion;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocContents;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.After;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Claim;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Content;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.DocRow;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Link;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Linked;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Paragraph;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Received;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.TaskRef;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.application.similar.SimilarTaskService;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec.Assignee;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Văn bản chỉ đạo đến và nhiệm vụ từ văn bản (QT-05 bước 1b–7, SCR-0502, CORE-02-16).
 *
 * <p>EVT-24 chỉ mang siêu dữ liệu; nội dung và đơn vị nhận lấy ở SVC-13. Văn bản lưu ngay khi nhận
 * để hiện trong danh sách, kể cả khi SVC-14 chưa trích hoặc không trích được (người giao vẫn tạo
 * nhiệm vụ thủ công). MOD-02 không gửi yêu cầu trích: SVC-14 cũng nhận EVT-24 và tự trích; MOD-02
 * chỉ đọc kết quả khi người giao mở văn bản, và không gọi SVC-14 khi chính SVC-14 đọc văn bản (vai
 * trò {@code ai_readonly}). Văn bản mức khác C0, C1 không đưa nội dung cho SVC-14.
 *
 * <p>Mỗi đoạn giao việc chỉ sinh hoặc gộp nhiệm vụ một lần (BR-05-07, khoá {@code
 * incoming_doc_claim}); nhiệm vụ do AI trích chỉ giao được khi đã xác nhận (BR-05-05, MSG-05-08).
 */
public class IncomingDocService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String DUPLICATE = "TASK-409-DUPLICATE-PARAGRAPH";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";
  static final String AI_ROLE = "ai_readonly";
  static final String SYSTEM_SOURCE = "VAN_BAN";
  static final String TEMPLATE_MERGED = "VAN_BAN_GOP_VAO_NHIEM_VU";
  static final Set<String> READABLE = Set.of("C0", "C1");
  static final Set<String> FIELD_NAMES =
      Set.of(
          "title",
          "leadUnitId",
          "coUnits",
          "dueDate",
          "productName",
          "catalogItemId",
          "keyResultId");
  static final Set<String> CLOSED = Set.of("HOAN_THANH", "HUY");
  static final String MSG_05_03 =
      "Có nhiệm vụ gần giống trong đơn vị và kỳ. Vui lòng gộp vào nhiệm vụ đã có hoặc ghi lý do vẫn"
          + " tạo mới.";
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final Logger LOG = LoggerFactory.getLogger(IncomingDocService.class);

  private final IncomingDocs docs;
  private final IncomingDocContents contents;
  private final DocExtractions extractions;
  private final TaskDraftService drafts;
  private final TaskAssignService assign;
  private final SimilarTaskService similar;
  private final TaskReminderNotices notices;
  private final PeriodApi periods;
  private final OrgDirectoryApi org;
  private final Function<UUID, List<UUID>> leadsOf;
  private final TransactionOperations perItem;
  private final Clock clock;

  /**
   * @param contents {@code null} khi chưa cấu hình SVC-13: văn bản chỉ có siêu dữ liệu
   * @param extractions {@code null} khi chưa cấu hình SVC-14: văn bản coi như đang trích
   * @param leadsOf người nhận thông báo gộp của một nhiệm vụ (người chủ trì hoặc lãnh đạo đơn vị
   *     chủ trì), theo mã nhiệm vụ trong tenant hiện hành
   */
  public IncomingDocService(
      IncomingDocs docs,
      IncomingDocContents contents,
      DocExtractions extractions,
      TaskDraftService drafts,
      TaskAssignService assign,
      SimilarTaskService similar,
      TaskReminderNotices notices,
      PeriodApi periods,
      OrgDirectoryApi org,
      Function<UUID, List<UUID>> leadsOf,
      TransactionOperations perItem,
      Clock clock) {
    this.docs = Objects.requireNonNull(docs, "docs");
    this.contents = contents;
    this.extractions = extractions;
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.assign = Objects.requireNonNull(assign, "assign");
    this.similar = similar;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.org = Objects.requireNonNull(org, "org");
    this.leadsOf = Objects.requireNonNull(leadsOf, "leadsOf");
    this.perItem = Objects.requireNonNull(perItem, "perItem");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Văn bản kèm kết quả trích (có thể vắng) và các đoạn đã sinh nhiệm vụ. */
  public record DocView(DocRow doc, Extraction extraction, List<Claim> claims) {

    public String extractionStatus() {
      return extraction == null ? "DANG_TRICH" : extraction.status();
    }

    public int suggestionCount() {
      return extraction == null ? 0 : extraction.suggestions().size();
    }

    /** Gợi ý còn chờ xác nhận: chưa có quyết định ở SVC-14 và đoạn chưa sinh nhiệm vụ. */
    public int pendingCount() {
      if (extraction == null) {
        return 0;
      }
      Set<Integer> claimed = claims.stream().map(Claim::paragraphNo).collect(Collectors.toSet());
      return (int)
          extraction.suggestions().stream()
              .filter(s -> "CHO_XAC_NHAN".equals(s.decision()))
              .filter(s -> !claimed.contains(s.paragraphNo()))
              .count();
    }

    /** Số đoạn AI xác định là giao việc cho đơn vị nhận. */
    public Set<Integer> assignmentParagraphs() {
      if (extraction == null) {
        return Set.of();
      }
      return extraction.suggestions().stream()
          .map(Suggestion::paragraphNo)
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  /** Một trang danh sách. */
  public record Page(List<DocView> items, After next) {}

  /** Lệnh xác nhận một nhiệm vụ trích ({@code FromDocumentRequest}). */
  public record Confirm(
      UUID sourceDocId,
      UUID suggestionId,
      int paragraphNo,
      Fields fields,
      List<String> editedFields,
      String similarReason) {

    public Confirm {
      editedFields = List.copyOf(editedFields == null ? List.of() : editedFields);
    }
  }

  /** Kết quả xác nhận hoặc gộp: gợi ý (nếu SVC-14 trả được) và nhiệm vụ đích. */
  public record Outcome(
      Suggestion suggestion,
      String decision,
      int paragraphNo,
      Fields fields,
      List<String> editedFields,
      List<SimilarTaskService.Match> similar,
      UUID taskId,
      String taskCode,
      String taskStatus) {}

  /** Kết quả giao một nhiệm vụ trong lô. */
  public record BatchItem(
      UUID taskId, String code, boolean assigned, String status, String message) {}

  // ───────────────────────────── Nhận EVT-24 ─────────────────────────────

  /**
   * Áp một EVT-24 trong giao dịch inbox: ghi văn bản (hoặc cập nhật siêu dữ liệu khi phiên bản mới
   * hơn), rồi lấy nội dung và đơn vị nhận từ SVC-13. SVC-13 lỗi thì ném để inbox thử lại; SVC-13
   * không có nội dung thì giữ siêu dữ liệu, đoạn 1 là trích yếu.
   */
  public void onReceived(TenantId tenant, Received received) {
    docs.upsertReceived(tenant, received);
    if (contents == null) {
      return;
    }
    Optional<Content> content = contents.fetch(tenant, received.id());
    content.ifPresent(c -> docs.storeContent(tenant, received.id(), c));
  }

  // ───────────────────────────── Xem ─────────────────────────────

  /** Văn bản đến của đơn vị người gọi ({@code listSourceDocs}). */
  @Transactional(value = TX, readOnly = true)
  public Page list(
      TenantId tenant,
      TaskActor actor,
      String status,
      int limit,
      After after,
      String authorization) {
    UUID unit = unitOf(tenant, actor.personId());
    if (unit == null) {
      return new Page(List.of(), null);
    }
    List<DocRow> rows = docs.list(tenant, unit, status, limit + 1, after);
    boolean more = rows.size() > limit;
    List<DocRow> page = more ? rows.subList(0, limit) : rows;
    List<DocView> items = new ArrayList<>();
    for (DocRow d : page) {
      items.add(view(tenant, d, authorization));
    }
    DocRow last = page.isEmpty() ? null : page.get(page.size() - 1);
    return new Page(items, more && last != null ? new After(last.receivedAt(), last.id()) : null);
  }

  /**
   * Toàn văn ({@code getSourceDoc}); chỉ đơn vị nhận xem (404 với đơn vị khác). SVC-14 đọc bằng vai
   * trò {@code ai_readonly}: không kiểm đơn vị, không gọi ngược SVC-14, và văn bản mức khác C0, C1
   * trả 404 để nội dung không vào lời nhắc.
   */
  @Transactional(value = TX, readOnly = true)
  public DocView get(TenantId tenant, TaskActor actor, UUID id, String authorization) {
    DocRow doc = docs.find(tenant, id).orElseThrow(NoSuchElementException::new);
    if (actor.roles().contains(AI_ROLE) && !actor.roles().contains("manager")) {
      if (!READABLE.contains(doc.classification())) {
        throw new NoSuchElementException();
      }
      return new DocView(doc, null, docs.claims(tenant, id));
    }
    requireRecipient(tenant, actor, doc);
    return view(tenant, doc, authorization);
  }

  // ───────────────────────────── Bỏ qua ─────────────────────────────

  /** Bỏ qua văn bản có lý do ({@code dismissSourceDoc}); văn bản không còn chờ xử lý trả 409. */
  @Transactional(TX)
  public DocView dismiss(
      TenantId tenant, TaskActor actor, UUID id, String reason, String authorization) {
    DocRow doc = docs.find(tenant, id).orElseThrow(NoSuchElementException::new);
    requireRecipient(tenant, actor, doc);
    String r = reason == null ? "" : reason.strip();
    if (r.isEmpty() || r.length() > 500) {
      throw invalid("reason", "Lý do bỏ qua bắt buộc, tối đa 500 ký tự", null);
    }
    if (!docs.dismiss(tenant, id, r, actor.personId(), clock.instant())) {
      throw new BusinessException(INVALID_STATE, "Văn bản đã được xử lý hoặc đã bỏ qua");
    }
    return view(tenant, docs.find(tenant, id).orElseThrow(), authorization);
  }

  // ───────────────────────────── Xác nhận ─────────────────────────────

  /**
   * Tạo nhiệm vụ {@code NHAP} từ một nhiệm vụ AI trích đã xác nhận ({@code
   * createTaskFromDocument}): chủ trì là đơn vị, người giao và người nghiệm thu là người gọi, căn
   * cứ là văn bản (BR-05-09). Khoá đoạn trước (409 {@code TASK-409-DUPLICATE-PARAGRAPH},
   * MSG-05-09), ghi liên kết kèm ô AI giữ nguyên, ô đã sửa (BR-05-05), kiểm nhiệm vụ gần giống
   * (BR-05-06: có thì bắt buộc lý do). Sau khi ghi, báo SVC-14 quyết định chấp nhận hoặc sửa (không
   * chặn).
   */
  @Transactional(TX)
  public Outcome createFromDocument(
      TenantId tenant, TaskActor actor, Confirm cmd, String authorization, String idempotencyKey) {
    DocRow doc = docs.find(tenant, cmd.sourceDocId()).orElseThrow(NoSuchElementException::new);
    requireRecipient(tenant, actor, doc);
    requireOpen(doc);
    requireParagraph(doc, cmd.paragraphNo());
    Fields f = cmd.fields();
    validate(f, cmd.editedFields());
    PeriodSnapshot period =
        periods
            .findCurrent(tenant)
            .orElseThrow(() -> new BusinessException(PERIOD_LOCKED, "Chưa có kỳ đang mở"));
    try {
      periods.assertWritable(tenant, period.id());
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không tạo được nhiệm vụ");
    }
    DraftRow draft = drafts.insertDraft(tenant, spec(doc, f, period, actor), actor, f.leadUnitId());
    Optional<Claim> taken =
        docs.claim(
            tenant,
            doc.id(),
            cmd.paragraphNo(),
            draft.id(),
            draft.periodBucket(),
            draft.code(),
            "TAO_MOI",
            actor.personId());
    if (taken.isPresent()) {
      throw duplicate(cmd.paragraphNo(), taken.get());
    }
    List<SimilarTaskService.Match> matches =
        similar == null
            ? List.of()
            : similar.similarTo(tenant, draft.id(), authorization).matches();
    String reason = cmd.similarReason() == null ? "" : cmd.similarReason().strip();
    if (!matches.isEmpty() && reason.isEmpty()) {
      throw new BusinessException(
          VALIDATION,
          MSG_05_03,
          List.of(ProblemFieldError.of("similarReason", MSG_05_03, "BR-05-06")),
          "MSG-05-03");
    }
    if (reason.length() > 1000) {
      throw invalid("similarReason", "Lý do không quá 1000 ký tự", null);
    }
    List<String> edited = cmd.editedFields().stream().distinct().toList();
    List<String> kept = FIELD_NAMES.stream().filter(n -> !edited.contains(n)).sorted().toList();
    docs.link(
        tenant,
        new Link(
            draft.periodId(),
            draft.periodBucket(),
            draft.id(),
            doc,
            cmd.paragraphNo(),
            true,
            cmd.suggestionId(),
            kept,
            edited,
            reason.isEmpty() ? null : reason),
        actor.personId());
    docs.markProcessed(tenant, doc.id(), actor.personId(), clock.instant());
    afterCommit(
        () ->
            acceptSuggestion(
                tenant,
                cmd.suggestionId(),
                edited,
                "task.task:" + draft.id(),
                authorization,
                idempotencyKey));
    return new Outcome(
        suggestionOf(tenant, doc.id(), cmd.suggestionId(), authorization),
        "DA_XAC_NHAN",
        cmd.paragraphNo(),
        f,
        edited,
        matches,
        draft.id(),
        draft.code(),
        draft.status());
  }

  // ───────────────────────────── Gộp ─────────────────────────────

  /**
   * Văn bản thành căn cứ bổ sung của nhiệm vụ cũ ({@code mergeSourceDocIntoTask}): khoá đoạn như
   * khi tạo mới, ghi liên kết, báo người chủ trì nhiệm vụ cũ qua EVT-21. Hạn và sản phẩm của nhiệm
   * vụ cũ chỉ đổi qua đề nghị điều chỉnh (QT-06).
   */
  @Transactional(TX)
  public Outcome mergeIntoTask(
      TenantId tenant,
      TaskActor actor,
      UUID taskId,
      UUID sourceDocId,
      UUID suggestionId,
      int paragraphNo,
      String authorization,
      String idempotencyKey) {
    DocRow doc = docs.find(tenant, sourceDocId).orElseThrow(NoSuchElementException::new);
    requireRecipient(tenant, actor, doc);
    requireOpen(doc);
    requireParagraph(doc, paragraphNo);
    TaskRef task = docs.task(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (!Objects.equals(task.orgUnitId(), doc.recipientUnitId())) {
      throw new NoSuchElementException();
    }
    if (CLOSED.contains(task.status())) {
      throw new BusinessException(
          INVALID_STATE, "Nhiệm vụ đã hoàn thành hoặc đã huỷ, không gộp được văn bản");
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không gộp được văn bản");
    }
    Optional<Claim> taken =
        docs.claim(
            tenant,
            doc.id(),
            paragraphNo,
            task.id(),
            task.periodBucket(),
            task.code(),
            "GOP",
            actor.personId());
    if (taken.isPresent()) {
      throw duplicate(paragraphNo, taken.get());
    }
    docs.link(
        tenant,
        new Link(
            task.periodId(),
            task.periodBucket(),
            task.id(),
            doc,
            paragraphNo,
            true,
            suggestionId,
            List.of(),
            List.of(),
            null),
        actor.personId());
    docs.markProcessed(tenant, doc.id(), actor.personId(), clock.instant());
    List<UUID> to =
        leadsOf.apply(task.id()).stream()
            .filter(Objects::nonNull)
            .filter(p -> !p.equals(actor.personId()))
            .distinct()
            .toList();
    if (!to.isEmpty()) {
      Map<String, String> p = new LinkedHashMap<>();
      p.put("maNhiemVu", task.code());
      p.put("tenNhiemVu", task.title());
      p.put("soVanBan", doc.docNumber());
      p.put("trichYeu", doc.excerpt());
      notices.notice(
          new TaskReminderNotices.Notice(
              tenant, task.id(), task.rowVersion(), TEMPLATE_MERGED, to, p, actor.personId()));
    }
    afterCommit(
        () ->
            acceptSuggestion(
                tenant,
                suggestionId,
                List.of(),
                "task.task:" + task.id(),
                authorization,
                idempotencyKey));
    return new Outcome(
        suggestionOf(tenant, doc.id(), suggestionId, authorization),
        "DA_GOP",
        paragraphNo,
        null,
        List.of(),
        List.of(),
        task.id(),
        task.code(),
        task.status());
  }

  // ───────────────────────────── Giao theo lô ─────────────────────────────

  /**
   * Giao các nhiệm vụ đã xác nhận ({@code assignTaskBatch}); mỗi nhiệm vụ một giao dịch qua {@link
   * TaskAssignService#assignTask}, nhiệm vụ lỗi không làm hỏng nhiệm vụ khác. Nhiệm vụ không phải
   * nhiệm vụ đã xác nhận từ văn bản của đơn vị người gọi bị chặn với MSG-05-08 (BR-05-05).
   */
  public List<BatchItem> batchAssign(
      TenantId tenant, TaskActor actor, List<UUID> taskIds, String authorization) {
    UUID unit = unitOf(tenant, actor.personId());
    List<BatchItem> out = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (UUID id : taskIds) {
      if (id == null || !seen.add(id)) {
        continue;
      }
      out.add(assignOne(tenant, actor, unit, id, authorization));
    }
    return out;
  }

  private BatchItem assignOne(
      TenantId tenant, TaskActor actor, UUID unit, UUID id, String authorization) {
    Optional<TaskRef> found = docs.task(tenant, id);
    if (found.isEmpty()) {
      return new BatchItem(id, "", false, "NHAP", "Không tìm thấy nhiệm vụ.");
    }
    TaskRef task = found.get();
    Optional<Linked> link = docs.linkOf(tenant, task.periodBucket(), task.id());
    boolean confirmed =
        SYSTEM_SOURCE.equals(task.source())
            && link.isPresent()
            && unit != null
            && unit.equals(link.get().recipientUnitId());
    if (!confirmed) {
      return new BatchItem(task.id(), task.code(), false, task.status(), msg0508(task.code()));
    }
    if (!"NHAP".equals(task.status())) {
      return new BatchItem(
          task.id(), task.code(), false, task.status(), "Nhiệm vụ " + task.code() + " đã giao.");
    }
    try {
      String reason = link.get().similarReason();
      TaskAssignService.Assigned done =
          perItem.execute(
              s ->
                  assign.assignTask(
                      tenant, task.id(), task.rowVersion(), reason, authorization, actor));
      return new BatchItem(
          task.id(), task.code(), true, done.task().status().name(), "Đã giao nhiệm vụ.");
    } catch (BusinessException e) {
      return new BatchItem(task.id(), task.code(), false, task.status(), reasonOf(e));
    } catch (NoSuchElementException e) {
      return new BatchItem(task.id(), task.code(), false, task.status(), msg0508(task.code()));
    }
  }

  /**
   * Lý do đầu tiên chặn giao (ví dụ chưa chọn người chủ trì), để người giao biết cần bổ sung gì.
   */
  static String reasonOf(BusinessException e) {
    String detail = e.detail() == null ? e.getMessage() : e.detail();
    return e.errors().isEmpty() ? detail : detail + ": " + e.errors().get(0).message();
  }

  static String msg0508(String code) {
    return "Nhiệm vụ " + code + " chưa được xác nhận nên chưa giao được.";
  }

  // ───────────────────────────── Phụ trợ ─────────────────────────────

  private DocView view(TenantId tenant, DocRow doc, String authorization) {
    return new DocView(doc, extraction(tenant, doc, authorization), docs.claims(tenant, doc.id()));
  }

  /** Kết quả trích; SVC-14 lỗi hoặc chưa cấu hình thì coi như đang trích (QT-05 "Ngoại lệ"). */
  private Extraction extraction(TenantId tenant, DocRow doc, String authorization) {
    if (!READABLE.contains(doc.classification())) {
      return new Extraction("KHONG_TRICH_DUOC", List.of());
    }
    if (extractions == null) {
      return null;
    }
    try {
      return extractions.find(tenant, doc.id(), authorization).orElse(null);
    } catch (DocExtractions.Unavailable e) {
      LOG.warn("Chưa đọc được kết quả trích của văn bản đến: {}", e.getMessage());
      return null;
    }
  }

  private Suggestion suggestionOf(
      TenantId tenant, UUID docId, UUID suggestionId, String authorization) {
    if (extractions == null || suggestionId == null) {
      return null;
    }
    try {
      return extractions
          .find(tenant, docId, authorization)
          .flatMap(
              e -> e.suggestions().stream().filter(s -> suggestionId.equals(s.id())).findFirst())
          .orElse(null);
    } catch (DocExtractions.Unavailable e) {
      return null;
    }
  }

  private void acceptSuggestion(
      TenantId tenant,
      UUID suggestionId,
      List<String> edited,
      String targetRef,
      String authorization,
      String idempotencyKey) {
    if (extractions == null || suggestionId == null) {
      return;
    }
    try {
      extractions.accept(
          tenant,
          suggestionId,
          !edited.isEmpty(),
          edited,
          targetRef,
          authorization,
          idempotencyKey == null ? "task-doc:" + suggestionId : idempotencyKey);
    } catch (DocExtractions.Unavailable | DependencyUnavailableException e) {
      // Nhiệm vụ đã ghi; nhật ký gợi ý của SVC-14 chỉ phục vụ đánh giá mô hình.
      LOG.warn("Chưa ghi được quyết định gợi ý ở SVC-14: {}", e.getMessage());
    } catch (RuntimeException e) {
      LOG.warn("SVC-14 từ chối quyết định gợi ý: {}", e.getMessage());
    }
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private TaskDraftSpec spec(DocRow doc, Fields f, PeriodSnapshot period, TaskActor actor) {
    String basis =
        "Văn bản "
            + doc.docNumber()
            + " ngày "
            + doc.docDate().format(VN_DATE)
            + " của "
            + doc.issuer();
    return new TaskDraftSpec(
        period.id().value(),
        SYSTEM_SOURCE,
        null,
        f.catalogItemId(),
        f.title().strip(),
        "THUONG_XUYEN",
        f.keyResultId(),
        "TRUNG_BINH",
        null,
        f.coUnits(),
        f.dueDate(),
        actor.personId(),
        List.of(),
        f.productName() == null ? "" : f.productName().strip(),
        null,
        "",
        "",
        "",
        doc.docNumber(),
        doc.excerpt(),
        basis.length() > 1000 ? basis.substring(0, 1000) : basis,
        null);
  }

  private static void validate(Fields f, List<String> edited) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (f == null) {
      throw invalid("fields", "Thiếu nội dung nhiệm vụ", null);
    }
    if (f.title() == null || f.title().isBlank() || f.title().length() > 500) {
      errors.add(ProblemFieldError.of("fields.title", "Tên nhiệm vụ bắt buộc, tối đa 500 ký tự"));
    }
    if (f.leadUnitId() == null) {
      errors.add(
          new ProblemFieldError("fields.leadUnitId", "Chưa chọn đơn vị chủ trì", "BR-05-01"));
    }
    if (f.dueDate() == null) {
      errors.add(new ProblemFieldError("fields.dueDate", "Chưa có hạn hoàn thành", "BR-05-03"));
    }
    if (f.productName() == null || f.productName().isBlank() || f.productName().length() > 500) {
      errors.add(
          new ProblemFieldError(
              "fields.productName", "Sản phẩm bắt buộc, tối đa 500 ký tự", "BR-05-03"));
    }
    if (f.coUnits().stream()
        .anyMatch(a -> a.id() == null || a.id().equals(f.leadUnitId()) || !isKind(a))) {
      errors.add(ProblemFieldError.of("fields.coUnits", "Đơn vị phối hợp không hợp lệ"));
    }
    if (!FIELD_NAMES.containsAll(edited)) {
      errors.add(ProblemFieldError.of("editedFields", "Tên ô đã sửa không hợp lệ"));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Thông tin nhiệm vụ chưa hợp lệ", errors);
    }
  }

  private static boolean isKind(Assignee a) {
    return "UNIT".equals(a.kind()) || "PERSON".equals(a.kind());
  }

  private static void requireOpen(DocRow doc) {
    if ("DA_BO_QUA".equals(doc.status())) {
      throw new BusinessException(INVALID_STATE, "Văn bản đã bị bỏ qua, không tạo được nhiệm vụ");
    }
  }

  /** Số đoạn phải có trong văn bản khi đã lấy được nội dung. */
  private static void requireParagraph(DocRow doc, int no) {
    boolean exists =
        no >= 1
            && (doc.paragraphs().isEmpty()
                || doc.paragraphs().stream().map(Paragraph::no).anyMatch(n -> n == no));
    if (!exists) {
      throw invalid("paragraphNo", "Văn bản không có đoạn " + no, null);
    }
  }

  private void requireRecipient(TenantId tenant, TaskActor actor, DocRow doc) {
    UUID unit = unitOf(tenant, actor.personId());
    if (unit == null || !unit.equals(doc.recipientUnitId())) {
      // Không lộ văn bản của đơn vị khác (SCR-0502: chỉ đơn vị nhận).
      throw new NoSuchElementException();
    }
  }

  private UUID unitOf(TenantId tenant, UUID person) {
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    return org.assignmentsOf(tenant, person, today).stream()
        .findFirst()
        .map(AssignmentView::orgUnitId)
        .orElse(null);
  }

  private static BusinessException duplicate(int paragraphNo, Claim existing) {
    return new BusinessException(
        DUPLICATE,
        "Đoạn " + paragraphNo + " của văn bản đã được tạo nhiệm vụ " + existing.taskCode() + ".",
        List.of(
            new ProblemFieldError(
                "paragraphNo",
                "Đoạn " + paragraphNo + " đã được tạo nhiệm vụ " + existing.taskCode(),
                "BR-05-07")),
        "MSG-05-09");
  }

  private static BusinessException invalid(String field, String message, String rule) {
    return new BusinessException(
        VALIDATION, message, List.of(ProblemFieldError.of(field, message, rule)));
  }

  /** Người gọi hệ thống cho bản ghi do consumer tạo. */
  public static UUID systemActor() {
    return SYSTEM_ACTOR;
  }

  /** Thời điểm hiện tại theo đồng hồ của dịch vụ (dùng cho consumer). */
  public Instant now() {
    return clock.instant();
  }
}
