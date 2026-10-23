package vn.viettel.okrkpi.product.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.products.model.Deliverable;
import vn.viettel.okrkpi.contract.products.model.DeliverableCreate;
import vn.viettel.okrkpi.contract.products.model.DeliverableList;
import vn.viettel.okrkpi.contract.products.model.DeliverableStatus;
import vn.viettel.okrkpi.contract.products.model.DeliverableType;
import vn.viettel.okrkpi.contract.products.model.Evidence;
import vn.viettel.okrkpi.contract.products.model.EvidenceAcceptRequest;
import vn.viettel.okrkpi.contract.products.model.EvidenceAcceptance;
import vn.viettel.okrkpi.contract.products.model.EvidenceAcceptanceDecision;
import vn.viettel.okrkpi.contract.products.model.EvidenceCreate;
import vn.viettel.okrkpi.contract.products.model.EvidenceCriterionResult;
import vn.viettel.okrkpi.contract.products.model.EvidenceDecisionResult;
import vn.viettel.okrkpi.contract.products.model.EvidenceDuplicateMatch;
import vn.viettel.okrkpi.contract.products.model.EvidenceExternalDoc;
import vn.viettel.okrkpi.contract.products.model.EvidenceExternalDocList;
import vn.viettel.okrkpi.contract.products.model.EvidenceKeyWrap;
import vn.viettel.okrkpi.contract.products.model.EvidenceKeyWrapBatchInput;
import vn.viettel.okrkpi.contract.products.model.EvidenceList;
import vn.viettel.okrkpi.contract.products.model.EvidenceRecoveryWrapRequest;
import vn.viettel.okrkpi.contract.products.model.EvidenceReturnRequest;
import vn.viettel.okrkpi.contract.products.model.EvidenceSourceType;
import vn.viettel.okrkpi.contract.products.model.EvidenceSubmit;
import vn.viettel.okrkpi.contract.products.model.EvidenceSubmitResult;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersion;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersionDraft;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersionFile;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersionFileInput;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersionSource;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersionStatus;
import vn.viettel.okrkpi.contract.products.model.EvidenceVersionSummary;
import vn.viettel.okrkpi.contract.products.model.FileScanStatus;
import vn.viettel.okrkpi.contract.products.model.ProductTaskRef;
import vn.viettel.okrkpi.contract.products.model.QualityLevel;
import vn.viettel.okrkpi.contract.products.model.Ref;
import vn.viettel.okrkpi.contract.products.model.RevisionCause;
import vn.viettel.okrkpi.product.application.e2ee.EvidenceKeyWrapService;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAcceptanceService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAcceptanceService.AcceptInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAcceptanceService.AcceptanceView;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAcceptanceService.Decision;
import vn.viettel.okrkpi.product.application.evidence.EvidenceActor;
import vn.viettel.okrkpi.product.application.evidence.EvidenceAiConfirmationService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceExternalDocService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceReviewService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.DraftInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.EvidenceDetail;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.FileDetail;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.FileInput;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.SubmitOutcome;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService.VersionDetail;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DeliverableView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceFilter;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionSummary;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments;
import vn.viettel.okrkpi.product.domain.evidence.CriterionResult;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.TaskSummary;

/**
 * Mười thao tác khai báo sản phẩm và nộp minh chứng của {@code products.yaml} (QT-07, SCR-0701,
 * CORE-03-03) và mở nghiệm thu, hàng chờ người nghiệm thu (SCR-0703, CORE-03-06). Tên tệp gốc là C2
 * ở SVC-08 và sự kiện lưu không mang tên tệp, nên {@code files[].fileName} trả rỗng (suy luận, wiki
 * 14); tên người trong {@code Ref} cũng rỗng như các API khác của mission-core.
 */
@RestController
@RequestMapping("/api/v1")
public class EvidenceController {

  private static final String MODULE = "products";

  private final EvidenceSubmissionService service;
  private final EvidenceAiConfirmationService aiConfirmation;
  private final EvidenceExternalDocService externalDocs;
  private final EvidenceReviewService reviews;
  private final EvidenceAcceptanceService acceptances;
  private final CursorCodec cursors;
  private final ObjectProvider<EvidenceCaller> callers;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final EvidenceKeyWrapService keyWraps;

  public EvidenceController(
      EvidenceSubmissionService service,
      EvidenceAiConfirmationService aiConfirmation,
      EvidenceExternalDocService externalDocs,
      EvidenceReviewService reviews,
      EvidenceAcceptanceService acceptances,
      CursorCodec cursors,
      ObjectProvider<EvidenceCaller> callers,
      ObjectProvider<AuthzSubjectResolver> subjects,
      EvidenceKeyWrapService keyWraps) {
    this.keyWraps = Objects.requireNonNull(keyWraps, "keyWraps");
    this.service = Objects.requireNonNull(service, "service");
    this.aiConfirmation = Objects.requireNonNull(aiConfirmation, "aiConfirmation");
    this.externalDocs = Objects.requireNonNull(externalDocs, "externalDocs");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.acceptances = Objects.requireNonNull(acceptances, "acceptances");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
  }

  // ---------------------------------------------------------------- sản phẩm

  @GetMapping("/products")
  @RequiresPermission(operationId = "listDeliverables", module = MODULE, resourceType = "product")
  public DeliverableList listDeliverables(
      @RequestParam("task") UUID task,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page = cursors.pageRequest(limit, cursor, "products:" + task);
    List<DeliverableView> rows =
        service.listDeliverables(
            tenant, task, page.afterKey().map(k -> k.getUuid(0)).orElse(null), page.fetchSize());
    CursorPage<DeliverableView> result = cursors.page(rows, page, d -> CursorKey.of(d.id()));
    return new DeliverableList()
        .items(result.items().stream().map(EvidenceController::deliverable).toList())
        .nextCursor(result.nextCursor());
  }

  @PostMapping("/products")
  @Idempotent
  @RequiresPermission(operationId = "createDeliverable", module = MODULE, resourceType = "product")
  public ResponseEntity<Deliverable> createDeliverable(@RequestBody DeliverableCreate body) {
    if (body == null) {
      throw invalid("taskId");
    }
    require(body.getTaskId(), "taskId");
    requireText(body.getName(), "name", 500);
    require(body.getDeliverableType(), "deliverableType");
    requireText(body.getUnit(), "unit", 32);
    if (body.getQuantityPlanned() == null
        || body.getQuantityPlanned().compareTo(BigDecimal.ZERO) <= 0) {
      throw invalid("quantityPlanned");
    }
    DeliverableView d =
        service.createDeliverable(
            TenantContext.require(),
            new EvidenceSubmissionService.DeliverableInput(
                body.getTaskId(),
                body.getName(),
                body.getDeliverableType().getValue(),
                body.getUnit(),
                body.getQuantityPlanned()),
            actor());
    return created("/api/v1/products/" + d.id(), deliverable(d), d.rowVersion());
  }

  @GetMapping("/products/{id}")
  @RequiresPermission(operationId = "getDeliverable", module = MODULE, resourceType = "product")
  public ResponseEntity<Deliverable> getDeliverable(@PathVariable("id") UUID id) {
    DeliverableView d = found(() -> service.getDeliverable(TenantContext.require(), id));
    return ETags.ok(deliverable(d), d.rowVersion());
  }

  // ---------------------------------------------------------------- minh chứng

  @GetMapping("/evidences")
  @RequiresPermission(operationId = "listEvidences", module = MODULE, resourceType = "evidence")
  public EvidenceList listEvidences(
      @RequestParam(value = "task", required = false) UUID task,
      @RequestParam(value = "deliverable", required = false) UUID deliverable,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "reviewer", required = false) String reviewer,
      @RequestParam(value = "submitter", required = false) String submitter,
      @RequestParam(value = "periodId", required = false) UUID periodId,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    UUID reviewerId = null;
    if (reviewer != null) {
      if (!"me".equals(reviewer)) {
        throw invalid("reviewer");
      }
      reviewerId = actor().personId();
    }
    UUID submitterId = null;
    if (submitter != null) {
      if (!"me".equals(submitter)) {
        throw invalid("submitter");
      }
      submitterId = actor().personId();
    }
    Set<String> statuses = new LinkedHashSet<>();
    if (status != null && !status.isBlank()) {
      for (String s : status.split(",")) {
        try {
          statuses.add(EvidenceVersionStatus.fromValue(s.strip()).getValue());
        } catch (IllegalArgumentException e) {
          throw invalid("status");
        }
      }
    }
    String scope =
        "evidences:"
            + task
            + ":"
            + deliverable
            + ":"
            + statuses
            + ":"
            + submitterId
            + ":"
            + reviewerId
            + ":"
            + periodId;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    List<EvidenceView> rows =
        service.listEvidences(
            tenant,
            new EvidenceFilter(task, deliverable, statuses, submitterId, reviewerId, periodId),
            page.afterKey().map(k -> k.getUuid(0)).orElse(null),
            page.fetchSize());
    CursorPage<EvidenceView> result = cursors.page(rows, page, e -> CursorKey.of(e.id()));
    Map<UUID, Optional<TaskSummary>> tasks = new HashMap<>();
    return new EvidenceList()
        .items(
            result.items().stream()
                .map(
                    e ->
                        evidence(
                            e,
                            tasks
                                .computeIfAbsent(e.taskId(), t -> service.taskOf(tenant, t))
                                .orElse(null),
                            null))
                .toList())
        .nextCursor(result.nextCursor());
  }

  @PostMapping("/evidences")
  @Idempotent
  @RequiresPermission(operationId = "createEvidence", module = MODULE, resourceType = "evidence")
  public ResponseEntity<Evidence> createEvidence(@RequestBody EvidenceCreate body) {
    if (body == null) {
      throw invalid("deliverableId");
    }
    require(body.getDeliverableId(), "deliverableId");
    requireText(body.getTitle(), "title", 500);
    require(body.getSourceType(), "sourceType");
    if (body.getExternalDocRef() != null && body.getExternalDocRef().length() > 200) {
      throw invalid("externalDocRef");
    }
    EvidenceDetail d =
        externalDocs.newEvidence(
            TenantContext.require(),
            new EvidenceSubmissionService.EvidenceInput(
                body.getDeliverableId(),
                body.getTitle(),
                body.getSourceType().getValue(),
                body.getExternalDocRef()),
            actor());
    return created(
        "/api/v1/evidences/" + d.evidence().id(),
        evidence(d.evidence(), d.task(), d.versions()),
        d.evidence().rowVersion());
  }

  @GetMapping("/evidences/external-docs")
  @RequiresPermission(
      operationId = "searchEvidenceExternalDocs",
      module = MODULE,
      resourceType = "evidence")
  public EvidenceExternalDocList searchEvidenceExternalDocs(
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (limit < 1 || limit > 200) {
      throw invalid("limit");
    }
    if (q != null && q.length() > 200) {
      throw invalid("q");
    }
    IssuedDocuments.Page page =
        externalDocs.search(TenantContext.require(), q, limit, cursor, actor());
    return new EvidenceExternalDocList()
        .items(
            page.items().stream()
                .map(
                    d ->
                        new EvidenceExternalDoc()
                            .ref(d.ref())
                            .docNumber(d.docNumber())
                            .docDate(d.docDate())
                            .summary(d.summary())
                            .signerName(d.signerName())
                            .signatureValid(d.signatureValid()))
                .toList())
        .nextCursor(page.nextCursor());
  }

  @GetMapping("/evidences/{id}")
  @RequiresPermission(operationId = "getEvidence", module = MODULE, resourceType = "evidence")
  public ResponseEntity<Evidence> getEvidence(@PathVariable("id") UUID id) {
    EvidenceDetail d = found(() -> service.getEvidence(TenantContext.require(), id));
    return ETags.ok(evidence(d.evidence(), d.task(), d.versions()), d.evidence().rowVersion());
  }

  // ---------------------------------------------------------------- phiên bản

  @PostMapping("/evidences/{id}/versions")
  @Idempotent
  @RequiresPermission(
      operationId = "createEvidenceVersion",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceVersion> createEvidenceVersion(
      @PathVariable("id") UUID id, @RequestBody EvidenceVersionDraft body) {
    DraftInput input = draft(body);
    EvidenceActor actor = actor();
    VersionDetail v =
        found(() -> externalDocs.newVersion(TenantContext.require(), id, input, actor));
    return created(
        "/api/v1/evidences/" + id + "/versions/" + v.version().versionNo(),
        version(v),
        v.version().rowVersion());
  }

  @GetMapping("/evidences/{id}/versions/{v}")
  @RequiresPermission(
      operationId = "getEvidenceVersion",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceVersion> getEvidenceVersion(
      @PathVariable("id") UUID id, @PathVariable("v") int versionNo) {
    TenantId tenant = TenantContext.require();
    VersionDetail v = found(() -> service.getVersion(tenant, id, versionNo));
    EvidenceVersion dto = version(v);
    if (v.version().hasE2ee()) {
      dto.setKeyWrap(
          keyWraps
              .mine(tenant, v.version(), actor().personId())
              .map(EvidenceController::keyWrap)
              .orElse(null));
    }
    return ETags.ok(dto, v.version().rowVersion());
  }

  @PostMapping("/evidences/{id}/versions/{v}/key-wraps")
  @Idempotent
  @RequiresPermission(
      operationId = "addEvidenceVersionKeyWraps",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceVersion> addEvidenceVersionKeyWraps(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestBody EvidenceKeyWrapBatchInput body) {
    TenantId tenant = TenantContext.require();
    EvidenceActor actor = actor();
    List<EvidenceKeyWrapService.WrapInput> input =
        body == null || body.getKeyWraps() == null
            ? null
            : body.getKeyWraps().stream()
                .map(
                    w ->
                        w == null
                            ? null
                            : new EvidenceKeyWrapService.WrapInput(
                                w.getRecipientId(),
                                w.getWrappedKey(),
                                w.getAlg() == null ? null : w.getAlg().getValue()))
                .toList();
    VersionDetail v = found(() -> keyWraps.add(tenant, id, versionNo, input, actor));
    EvidenceVersion dto = version(v);
    dto.setKeyWrap(
        keyWraps
            .mine(tenant, v.version(), actor.personId())
            .map(EvidenceController::keyWrap)
            .orElse(null));
    return ETags.ok(dto, v.version().rowVersion());
  }

  @PostMapping("/evidences/{id}/versions/{v}/recovery-wraps")
  @Idempotent
  @RequiresPermission(
      operationId = "restoreEvidenceKeyWrap",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceVersion> restoreEvidenceKeyWrap(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestBody EvidenceRecoveryWrapRequest body) {
    TenantId tenant = TenantContext.require();
    EvidenceActor actor = actor();
    EvidenceKeyWrap w = body == null ? null : body.getKeyWrap();
    EvidenceKeyWrapService.WrapInput input =
        w == null
            ? null
            : new EvidenceKeyWrapService.WrapInput(
                w.getRecipientId(),
                w.getWrappedKey(),
                w.getAlg() == null ? null : w.getAlg().getValue());
    VersionDetail v =
        found(
            () ->
                keyWraps.restore(
                    tenant,
                    id,
                    versionNo,
                    body == null ? null : body.getRecoveryId(),
                    input,
                    actor));
    return ETags.ok(version(v), v.version().rowVersion());
  }

  private static EvidenceKeyWrap keyWrap(EvidenceKeyWraps.StoredWrap w) {
    return new EvidenceKeyWrap()
        .recipientId(w.recipientId())
        .wrappedKey(java.util.Base64.getEncoder().encodeToString(w.wrappedKey()))
        .alg(EvidenceKeyWrap.AlgEnum.fromValue(w.algorithm()));
  }

  @PutMapping("/evidences/{id}/versions/{v}")
  @Idempotent
  @RequiresPermission(
      operationId = "updateEvidenceVersionDraft",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceVersion> updateEvidenceVersionDraft(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody EvidenceVersionDraft body) {
    long expected = ETags.expectedVersion(ifMatch, "PRODUCT");
    DraftInput input = draft(body);
    EvidenceActor actor = actor();
    VersionDetail v =
        found(
            () ->
                service.updateDraft(
                    TenantContext.require(), id, versionNo, expected, input, actor));
    return ETags.ok(version(v), v.version().rowVersion());
  }

  @PostMapping("/evidences/{id}/versions/{v}/submit")
  @Idempotent
  @RequiresPermission(
      operationId = "submitEvidenceVersion",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceSubmitResult> submitEvidenceVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) EvidenceSubmit body) {
    long expected = ETags.expectedVersion(ifMatch, "PRODUCT");
    boolean attested =
        body != null
            && body.getAttested() != null
            && Boolean.TRUE.equals(body.getAttested().getValue());
    boolean aiConfirmed = body != null && Boolean.TRUE.equals(body.getAiFieldsConfirmed());
    EvidenceActor actor = actor();
    SubmitOutcome done =
        found(
            () ->
                aiConfirmation.submit(
                    TenantContext.require(),
                    id,
                    versionNo,
                    expected,
                    attested,
                    aiConfirmed,
                    actor,
                    authorization));
    EvidenceSubmitResult result =
        new EvidenceSubmitResult()
            .version(version(done.version()))
            .reviewer(done.reviewerId() == null ? null : new Ref().id(done.reviewerId()).name(""))
            .taskStatusChanged(done.taskStatusChanged());
    return ETags.ok(result, done.version().version().rowVersion());
  }

  // ---------------------------------------------------------------- nghiệm thu

  @PostMapping("/evidences/{id}/versions/{v}/open-review")
  @Idempotent
  @RequiresPermission(
      operationId = "openEvidenceReview",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceVersion> openEvidenceReview(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    long expected = ETags.expectedVersion(ifMatch, "PRODUCT");
    EvidenceActor actor = actor();
    TenantId tenant = TenantContext.require();
    found(() -> reviews.openReview(tenant, id, versionNo, expected, actor));
    VersionDetail v = found(() -> service.getVersion(tenant, id, versionNo));
    return ETags.ok(version(v), v.version().rowVersion());
  }

  @PostMapping("/evidences/{id}/versions/{v}/accept")
  @Idempotent
  @RequiresPermission(
      operationId = "acceptEvidenceVersion",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceDecisionResult> acceptEvidenceVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody EvidenceAcceptRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "PRODUCT");
    if (body == null || body.getQualityLevel() == null) {
      throw invalid("qualityLevel");
    }
    AcceptInput input =
        new AcceptInput(
            body.getQualityLevel().getValue(),
            criteria(body.getCriteriaResults()),
            body.getRevisionCause() == null ? null : body.getRevisionCause().getValue(),
            body.getIsObjectiveRevision(),
            body.getComment());
    EvidenceActor actor = actor();
    Decision d =
        found(
            () ->
                acceptances.acceptVersion(
                    TenantContext.require(), id, versionNo, expected, input, actor));
    return ETags.ok(decision(d), d.rowVersion());
  }

  @PostMapping("/evidences/{id}/versions/{v}/request-revision")
  @Idempotent
  @RequiresPermission(
      operationId = "requestEvidenceRevision",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceDecisionResult> requestEvidenceRevision(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody EvidenceReturnRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "PRODUCT");
    EvidenceActor actor = actor();
    Decision d =
        found(
            () ->
                acceptances.requestRevision(
                    TenantContext.require(),
                    id,
                    versionNo,
                    expected,
                    criteria(body == null ? null : body.getCriteriaResults()),
                    body == null ? null : body.getComment(),
                    actor));
    return ETags.ok(decision(d), d.rowVersion());
  }

  @PostMapping("/evidences/{id}/versions/{v}/reject")
  @Idempotent
  @RequiresPermission(
      operationId = "rejectEvidenceVersion",
      module = MODULE,
      resourceType = "evidence")
  public ResponseEntity<EvidenceDecisionResult> rejectEvidenceVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int versionNo,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody EvidenceReturnRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "PRODUCT");
    EvidenceActor actor = actor();
    Decision d =
        found(
            () ->
                acceptances.rejectVersion(
                    TenantContext.require(),
                    id,
                    versionNo,
                    expected,
                    criteria(body == null ? null : body.getCriteriaResults()),
                    body == null ? null : body.getComment(),
                    actor));
    return ETags.ok(decision(d), d.rowVersion());
  }

  // ---------------------------------------------------------------- ánh xạ

  private static List<CriterionResult> criteria(List<EvidenceCriterionResult> items) {
    if (items == null) {
      return List.of();
    }
    return items.stream()
        .map(
            c ->
                new CriterionResult(
                    c.getCriterionCode(), Boolean.TRUE.equals(c.getPassed()), c.getNote()))
        .toList();
  }

  static EvidenceDecisionResult decision(Decision d) {
    AcceptanceView a = d.acceptance();
    EvidenceAcceptance dto =
        new EvidenceAcceptance()
            .id(a.id())
            .evidenceVersionId(a.versionId())
            .decision(EvidenceAcceptanceDecision.fromValue(a.decision()))
            .qualityLevel(
                a.qualityLevel() == null ? null : QualityLevel.fromValue(a.qualityLevel()))
            .criteriaResults(
                a.criteria().stream()
                    .map(
                        c ->
                            new EvidenceCriterionResult()
                                .criterionCode(c.code())
                                .passed(c.passed())
                                .note(c.note()))
                    .toList())
            .revisionCause(
                a.revisionCause() == null ? null : RevisionCause.fromValue(a.revisionCause()))
            .isObjectiveRevision(a.objectiveRevision())
            .revisionCount(a.revisionCount())
            .comment(a.comment())
            .reviewer(new Ref().id(a.reviewerId()).name(""))
            .onBehalfOf(a.onBehalfOf() == null ? null : new Ref().id(a.onBehalfOf()).name(""))
            .delegationId(a.delegationId())
            .decidedAt(time(a.decidedAt()));
    return new EvidenceDecisionResult()
        .acceptance(dto)
        .versionStatus(EvidenceVersionStatus.fromValue(d.versionStatus()))
        .taskStatusChanged(d.taskStatusChanged());
  }

  static Deliverable deliverable(DeliverableView d) {
    return new Deliverable()
        .id(d.id())
        .taskId(d.taskId())
        .name(d.name())
        .deliverableType(DeliverableType.fromValue(d.deliverableType()))
        .unit(d.unit())
        .quantityPlanned(d.quantityPlanned().stripTrailingZeros())
        .quantityAccepted(d.quantityAccepted().stripTrailingZeros())
        .status(DeliverableStatus.fromValue(d.status()))
        .rowVersion((int) d.rowVersion());
  }

  static Evidence evidence(EvidenceView e, TaskSummary task, List<VersionSummary> versions) {
    Evidence dto =
        new Evidence()
            .id(e.id())
            .deliverableId(e.deliverableId())
            .task(
                new ProductTaskRef()
                    .id(e.taskId())
                    .code(task == null ? "" : task.code())
                    .name(task == null ? "" : task.title()))
            .title(e.title())
            .sourceType(EvidenceSourceType.fromValue(e.sourceType()))
            .externalDocRef(e.externalDocRef())
            .currentVersionNo(e.currentVersionNo())
            .currentStatus(
                e.currentStatus() == null
                    ? null
                    : EvidenceVersionStatus.fromValue(e.currentStatus()))
            .isDuplicateSuspect(e.duplicateSuspect())
            .submittedBy(e.submittedBy() == null ? null : new Ref().id(e.submittedBy()).name(""))
            .submittedAt(time(e.submittedAt()))
            .rowVersion((int) e.rowVersion());
    if (versions != null) {
      dto.versions(
          versions.stream()
              .map(
                  v ->
                      new EvidenceVersionSummary()
                          .versionNo(v.versionNo())
                          .status(EvidenceVersionStatus.fromValue(v.status()))
                          .submittedAt(time(v.submittedAt()))
                          .decidedAt(time(v.acceptedAt())))
              .toList());
    }
    return dto;
  }

  static EvidenceVersion version(VersionDetail d) {
    VersionView v = d.version();
    return new EvidenceVersion()
        .id(v.id())
        .evidenceId(v.evidenceId())
        .taskId(v.taskId())
        .versionNo(v.versionNo())
        .status(EvidenceVersionStatus.fromValue(v.status()))
        .source(EvidenceVersionSource.fromValue(v.source()))
        .sourceDocRef(v.sourceDocRef())
        .files(d.files().stream().map(EvidenceController::file).toList())
        .scanStatus(FileScanStatus.fromValue(v.scanStatus()))
        .isDuplicateSuspect(v.duplicateSuspect())
        .hasE2ee(v.hasE2ee())
        .aiPrefill(v.aiPrefill())
        .aiSuggestionId(v.aiSuggestionId())
        .userConfirmedAt(time(v.userConfirmedAt()))
        .docNumber(v.docNumber())
        .docDate(v.docDate())
        .docSummary(v.docSummary())
        .note(v.note())
        .submittedBy(v.submittedBy() == null ? null : new Ref().id(v.submittedBy()).name(""))
        .submittedAt(time(v.submittedAt()))
        .attestedAt(time(v.attestedAt()))
        .acceptedAt(time(v.acceptedAt()))
        .excludedPeriodId(v.excludedPeriodId())
        .rowVersion((int) v.rowVersion());
  }

  private static EvidenceVersionFile file(FileDetail d) {
    var f = d.view().file();
    StoredObject o = d.view().object();
    EvidenceVersionFile dto =
        new EvidenceVersionFile()
            .objectId(f.objectId())
            .contentSha256(HexFormat.of().formatHex(f.contentSha256()))
            .sizeBytes(o == null || o.sizeBytes() == null ? 0 : Math.toIntExact(o.sizeBytes()))
            .mediaType(o == null || o.mediaType() == null ? "" : o.mediaType())
            .fileName("")
            .scanStatus(FileScanStatus.fromValue(f.scanStatus()))
            .isDuplicateSuspect(f.duplicateOfVersionId() != null)
            .reuseJustification(f.reuseJustification());
    if (d.duplicate() != null) {
      dto.duplicateOf(
          new EvidenceDuplicateMatch()
              .taskCode(d.duplicate().taskCode())
              .submittedAt(time(d.duplicate().submittedAt())));
    }
    return dto;
  }

  private static DraftInput draft(EvidenceVersionDraft body) {
    if (body == null || body.getSource() == null) {
      throw invalid("source");
    }
    List<EvidenceVersionFileInput> files = body.getFiles() == null ? List.of() : body.getFiles();
    if (body.getDocSummary() != null && body.getDocSummary().length() > 500) {
      throw invalid("docSummary");
    }
    if (body.getNote() != null && body.getNote().length() > 2000) {
      throw invalid("note");
    }
    return new DraftInput(
        body.getSource().getValue(),
        body.getSourceDocRef(),
        files.stream()
            .map(
                f ->
                    new FileInput(
                        f.getObjectId(), f.getObjectVersionNo(), f.getReuseJustification()))
            .toList(),
        Boolean.TRUE.equals(body.getAiPrefill()),
        body.getAiSuggestionId(),
        body.getDocNumber(),
        body.getDocDate(),
        body.getDocSummary(),
        body.getNote(),
        body.getAiFieldsEdited() == null
            ? List.of()
            : body.getAiFieldsEdited().stream().map(f -> f.getValue()).toList());
  }

  // ---------------------------------------------------------------- hỗ trợ

  private EvidenceActor actor() {
    EvidenceCaller caller = callers.getIfAvailable();
    if (caller != null) {
      return caller.current().orElseThrow(EvidenceController::unauthorized);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw unauthorized();
    }
    try {
      return new EvidenceActor(
          UUID.fromString(subject.userId()),
          new LinkedHashSet<>(subject.roles() == null ? List.of() : subject.roles()),
          subject.acr(),
          subject.authTime());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private static <T> T found(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static <T> ResponseEntity<T> created(String location, T body, long rowVersion) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create(location))
        .headers(ETags.headers(rowVersion))
        .body(body);
  }

  private static void require(Object value, String field) {
    if (value == null) {
      throw invalid(field);
    }
  }

  private static void requireText(String value, String field, int max) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw invalid(field);
    }
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static BusinessException invalid(String field) {
    String message = "Thông tin chưa hợp lệ";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
