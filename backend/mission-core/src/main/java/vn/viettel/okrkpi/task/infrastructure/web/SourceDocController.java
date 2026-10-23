package vn.viettel.okrkpi.task.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.AssignBatchResult;
import vn.viettel.okrkpi.contract.tasks.model.AssignTaskBatch200Response;
import vn.viettel.okrkpi.contract.tasks.model.AssignTaskBatchRequest;
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRef;
import vn.viettel.okrkpi.contract.tasks.model.DismissSourceDocRequest;
import vn.viettel.okrkpi.contract.tasks.model.FromDocumentRequest;
import vn.viettel.okrkpi.contract.tasks.model.ListSourceDocs200Response;
import vn.viettel.okrkpi.contract.tasks.model.MergeSourceDocIntoTaskRequest;
import vn.viettel.okrkpi.contract.tasks.model.SourceDoc;
import vn.viettel.okrkpi.contract.tasks.model.SourceDocAllOfParagraphs;
import vn.viettel.okrkpi.contract.tasks.model.SourceDocExtractionStatus;
import vn.viettel.okrkpi.contract.tasks.model.SourceDocStatus;
import vn.viettel.okrkpi.contract.tasks.model.SourceDocSummary;
import vn.viettel.okrkpi.contract.tasks.model.SourceSystem;
import vn.viettel.okrkpi.contract.tasks.model.SuggestionDecision;
import vn.viettel.okrkpi.contract.tasks.model.SuggestionField;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;
import vn.viettel.okrkpi.contract.tasks.model.TaskSuggestion;
import vn.viettel.okrkpi.contract.tasks.model.TaskSuggestionFields;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.document.IncomingDocService;
import vn.viettel.okrkpi.task.application.document.IncomingDocService.Confirm;
import vn.viettel.okrkpi.task.application.document.IncomingDocService.DocView;
import vn.viettel.okrkpi.task.application.document.IncomingDocService.Outcome;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Fields;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.After;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.DocRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec.Assignee;

/**
 * Văn bản đến và nhiệm vụ từ văn bản của QT-05 (SCR-0502, CORE-02-16): danh sách, toàn văn, bỏ qua,
 * xác nhận nhiệm vụ AI trích, gộp vào nhiệm vụ cũ, giao theo lô. Tên đơn vị phối hợp lấy từ gợi ý
 * của SVC-14 khi có, ngược lại để trống cho giao diện tự tra (như các API khác của MOD-02).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class SourceDocController {

  static final String SCOPE = "listSourceDocs";

  private final IncomingDocService docs;
  private final TaskCallers callers;
  private final CursorCodec cursors;

  public SourceDocController(IncomingDocService docs, TaskCallers callers, CursorCodec cursors) {
    this.docs = Objects.requireNonNull(docs, "docs");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @GetMapping("/source-docs")
  @RequiresPermission(operationId = "listSourceDocs", module = "tasks", resourceType = "task")
  public ListSourceDocs200Response listSourceDocs(
      @RequestParam(value = "status", required = false) SourceDocStatus status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    String scope = SCOPE + ":" + (status == null ? "" : status.getValue());
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    After after =
        page.after() == null
            ? null
            : new After(Instant.parse(page.after().getString(0)), page.after().getUuid(1));
    IncomingDocService.Page result =
        docs.list(
            TenantContext.require(),
            callers.current(),
            status == null ? null : status.getValue(),
            page.limit(),
            after,
            authorization);
    String next =
        result.next() == null
            ? null
            : cursors.encode(
                scope, CursorKey.of(result.next().receivedAt().toString(), result.next().id()));
    return new ListSourceDocs200Response(
        result.items().stream().map(SourceDocController::summary).toList(), next);
  }

  @GetMapping("/source-docs/{id}")
  @RequiresPermission(operationId = "getSourceDoc", module = "tasks", resourceType = "task")
  public SourceDoc getSourceDoc(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return doc(
        found(() -> docs.get(TenantContext.require(), callers.current(), id, authorization)));
  }

  @PostMapping("/source-docs/{id}/dismiss")
  @Idempotent
  @RequiresPermission(operationId = "dismissSourceDoc", module = "tasks", resourceType = "task")
  public SourceDoc dismissSourceDoc(
      @PathVariable("id") UUID id,
      @RequestBody DismissSourceDocRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return doc(
        found(
            () ->
                docs.dismiss(
                    TenantContext.require(),
                    callers.current(),
                    id,
                    body.getReason(),
                    authorization)));
  }

  @PostMapping("/from-document")
  @Idempotent
  @RequiresPermission(
      operationId = "createTaskFromDocument",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskSuggestion> createTaskFromDocument(
      @RequestBody FromDocumentRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    Confirm cmd =
        new Confirm(
            body.getSourceDocId(),
            body.getSuggestionId(),
            body.getParagraphNo() == null ? 0 : body.getParagraphNo(),
            fields(body.getFields()),
            body.getEditedFields() == null
                ? List.of()
                : body.getEditedFields().stream().map(SuggestionField::getValue).toList(),
            body.getSimilarReason());
    Outcome out =
        found(
            () ->
                docs.createFromDocument(
                    TenantContext.require(),
                    callers.current(),
                    cmd,
                    authorization,
                    idempotencyKey));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(suggestion(out, body.getSuggestionId(), body.getFields()));
  }

  @PostMapping("/{id}/source-docs")
  @Idempotent
  @RequiresPermission(
      operationId = "mergeSourceDocIntoTask",
      module = "tasks",
      resourceType = "task")
  public TaskSuggestion mergeSourceDocIntoTask(
      @PathVariable("id") UUID id,
      @RequestBody MergeSourceDocIntoTaskRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    Outcome out =
        found(
            () ->
                docs.mergeIntoTask(
                    TenantContext.require(),
                    callers.current(),
                    id,
                    body.getSourceDocId(),
                    body.getSuggestionId(),
                    body.getParagraphNo() == null ? 0 : body.getParagraphNo(),
                    authorization,
                    idempotencyKey));
    return suggestion(out, body.getSuggestionId(), null);
  }

  @PostMapping("/assign-batch")
  @Idempotent
  @RequiresPermission(operationId = "assignTaskBatch", module = "tasks", resourceType = "task")
  public AssignTaskBatch200Response assignTaskBatch(
      @RequestBody AssignTaskBatchRequest body,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    List<AssignBatchResult> results =
        docs
            .batchAssign(
                TenantContext.require(), callers.current(), body.getTaskIds(), authorization)
            .stream()
            .map(
                r ->
                    new AssignBatchResult(
                            r.taskId(), r.code(), r.assigned(), TaskStatus.fromValue(r.status()))
                        .message(r.message()))
            .toList();
    return new AssignTaskBatch200Response(results);
  }

  // ───────────────────────────── Ánh xạ ─────────────────────────────

  static SourceDocSummary summary(DocView v) {
    DocRow d = v.doc();
    return new SourceDocSummary(
        d.id(),
        SourceSystem.fromValue(d.sourceSystem()),
        d.docNumber(),
        d.docDate(),
        d.issuer(),
        d.excerpt(),
        time(d.receivedAt()),
        d.format(),
        SourceDocStatus.fromValue(d.status()),
        extraction(v),
        v.pendingCount(),
        v.suggestionCount());
  }

  static SourceDoc doc(DocView v) {
    DocRow d = v.doc();
    Set<Integer> assignment = v.assignmentParagraphs();
    List<SourceDocAllOfParagraphs> paragraphs =
        d.paragraphs().stream()
            .map(p -> new SourceDocAllOfParagraphs(p.no(), p.text(), assignment.contains(p.no())))
            .toList();
    return new SourceDoc(
            d.id(),
            SourceSystem.fromValue(d.sourceSystem()),
            d.docNumber(),
            d.docDate(),
            d.issuer(),
            d.excerpt(),
            time(d.receivedAt()),
            d.format(),
            SourceDocStatus.fromValue(d.status()),
            extraction(v),
            v.pendingCount(),
            v.suggestionCount(),
            nz(d.issuerHeader(), d.issuer()),
            nz(d.title(), d.excerpt()),
            nz(d.preamble(), ""),
            paragraphs,
            nz(d.recipients(), ""))
        .dismissReason(d.dismissReason());
  }

  static Fields fields(TaskSuggestionFields f) {
    if (f == null) {
      return null;
    }
    List<AssigneeRef> co = f.getCoUnits() == null ? List.of() : f.getCoUnits();
    return new Fields(
        f.getTitle(),
        f.getLeadUnitId(),
        f.getLeadUnitName(),
        co.stream()
            .map(a -> new Assignee(a.getKind() == null ? null : a.getKind().getValue(), a.getId()))
            .toList(),
        co.stream().map(a -> nz(a.getName(), "")).toList(),
        f.getDueDate(),
        f.getProductName(),
        f.getCatalogItemId(),
        f.getCatalogItemCode(),
        f.getKeyResultId(),
        f.getKeyResultCode());
  }

  static TaskSuggestionFields fieldsDto(Fields f) {
    if (f == null) {
      return null;
    }
    List<AssigneeRef> co = new java.util.ArrayList<>();
    for (int i = 0; i < f.coUnits().size(); i++) {
      Assignee a = f.coUnits().get(i);
      co.add(
          new AssigneeRef(
              AssigneeRef.KindEnum.fromValue(a.kind()),
              a.id(),
              i < f.coUnitNames().size() ? f.coUnitNames().get(i) : ""));
    }
    return new TaskSuggestionFields(
            nz(f.title(), ""),
            f.leadUnitId(),
            nz(f.leadUnitName(), ""),
            co,
            f.dueDate(),
            nz(f.productName(), ""))
        .catalogItemId(f.catalogItemId())
        .catalogItemCode(f.catalogItemCode())
        .keyResultId(f.keyResultId())
        .keyResultCode(f.keyResultCode());
  }

  /**
   * {@code TaskSuggestion} của kết quả; khi SVC-14 không trả được gợi ý thì ô AI lấy theo ô người
   * giao gửi lên (gộp: theo ô của gợi ý, vắng thì để trống).
   */
  static TaskSuggestion suggestion(Outcome out, UUID suggestionId, TaskSuggestionFields sent) {
    TaskSuggestionFields fields =
        out.fields() != null
            ? fieldsDto(out.fields())
            : out.suggestion() != null ? fieldsDto(out.suggestion().fields()) : sent;
    TaskSuggestionFields ai =
        out.suggestion() != null && out.suggestion().aiFields() != null
            ? fieldsDto(out.suggestion().aiFields())
            : fields;
    if (fields == null) {
      fields = new TaskSuggestionFields("", null, "", List.of(), null, "");
      ai = fields;
    }
    BigDecimal confidence =
        out.suggestion() == null ? BigDecimal.ONE : out.suggestion().confidence();
    return new TaskSuggestion(
            suggestionId,
            out.paragraphNo(),
            confidence,
            SuggestionDecision.fromValue(out.decision()),
            fields,
            ai,
            out.editedFields().stream().map(SuggestionField::fromValue).toList(),
            out.similar().stream().map(TaskSimilarController::dto).toList())
        .taskId(out.taskId())
        .taskCode(out.taskCode())
        .taskStatus(TaskStatus.fromValue(out.taskStatus()));
  }

  /** Giá trị lạ từ SVC-14 coi như đang trích. */
  static SourceDocExtractionStatus extraction(DocView v) {
    try {
      return SourceDocExtractionStatus.fromValue(v.extractionStatus());
    } catch (IllegalArgumentException e) {
      return SourceDocExtractionStatus.DANG_TRICH;
    }
  }

  private static String nz(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
