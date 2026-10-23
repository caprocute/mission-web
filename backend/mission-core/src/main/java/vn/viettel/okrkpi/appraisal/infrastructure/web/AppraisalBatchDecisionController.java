package vn.viettel.okrkpi.appraisal.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
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
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Command;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Item;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.View;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionSubmission;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRow;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.infrastructure.job.BatchDecisionJobs;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalBatchDecision;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalBatchDecisionInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalBatchDecisionItem;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalBatchDecisionJob;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalBatchDecisionPage;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalBatchDecisionStatus;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.JobStatus;
import vn.viettel.okrkpi.contract.appraisals.model.PeriodRef;
import vn.viettel.okrkpi.contract.appraisals.model.Ref;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.OpaSqlFilter;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.authz.ResourceColumnMapping;
import vn.viettel.okrkpi.shared.authz.SqlPredicate;
import vn.viettel.okrkpi.shared.authz.UntranslatablePolicyException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Quyết định xếp loại hàng loạt của {@code appraisals.yaml} (CORE-04-07, QT-09, SCR-0904): {@code
 * createAppraisalBatchDecision} (202, AAL2 một lần cho cả lô), {@code listAppraisalBatchDecisions},
 * {@code getAppraisalBatchDecision}, {@code cancelAppraisalBatchDecision}. Phạm vi {@code
 * SCR-0904.ky-lo} của người gọi dịch sang SQL bằng đánh giá từng phần của OPA và áp cho mọi hồ sơ
 * của lô bằng một truy vấn; tách vai trò BR-09-14 kiểm ở dịch vụ.
 */
@RestController
@RequestMapping("/api/v1/appraisals/batch-decisions")
public class AppraisalBatchDecisionController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;

  private final BatchDecisionService service;
  private final BatchDecisionSubmission submission;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OpaSqlFilter> filters;
  private final AppraisalProfileMapper mapper;

  public AppraisalBatchDecisionController(
      BatchDecisionService service,
      BatchDecisionSubmission submission,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OpaSqlFilter> filters,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<ObjectMapper> json) {
    this.service = Objects.requireNonNull(service, "service");
    this.submission = Objects.requireNonNull(submission, "submission");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.filters = Objects.requireNonNull(filters, "filters");
    this.mapper = new AppraisalProfileMapper(json.getIfAvailable(ObjectMapper::new), periods);
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listAppraisalBatchDecisions",
      module = "appraisals",
      action = "SCR-0904.theo-doi-huy")
  public ResponseEntity<AppraisalBatchDecisionPage> listAppraisalBatchDecisions(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "flow", required = false) String flow,
      @RequestParam(value = "status", required = false) List<String> status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    if (flow != null && !"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw invalid("flow", "Luồng đánh giá không hợp lệ.");
    }
    Set<String> statuses = new LinkedHashSet<>();
    if (status != null) {
      for (String s : status) {
        try {
          statuses.add(AppraisalBatchDecisionStatus.fromValue(s).getValue());
        } catch (IllegalArgumentException e) {
          throw invalid("status", "Trạng thái lô không hợp lệ.");
        }
      }
    }
    List<View> rows =
        service.page(
            tenant,
            actor(),
            period,
            flow,
            statuses,
            SelfAssessmentController.decodeCursor(cursor),
            size + 1);
    boolean more = rows.size() > size;
    List<View> page = more ? rows.subList(0, size) : rows;
    Map<UUID, PeriodRef> refs = new LinkedHashMap<>();
    List<AppraisalBatchDecision> items = new ArrayList<>();
    for (View v : page) {
      items.add(dto(tenant, v, refs));
    }
    String next =
        more ? SelfAssessmentController.encodeCursor(page.get(page.size() - 1).batch().id()) : null;
    return ResponseEntity.ok(new AppraisalBatchDecisionPage(items, next));
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(
      operationId = "createAppraisalBatchDecision",
      module = "appraisals",
      action = "SCR-0904.ky-lo",
      classification = "C2")
  public ResponseEntity<AppraisalBatchDecisionJob> createAppraisalBatchDecision(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody AppraisalBatchDecisionInput body) {
    TenantId tenant = TenantContext.require();
    if (body == null) {
      throw invalid("items", "Thiếu nội dung lô quyết định.");
    }
    UUID actor = actor();
    String flow = body.getFlow() == null ? null : body.getFlow().getValue();
    List<Item> items = new ArrayList<>();
    if (body.getItems() != null) {
      for (AppraisalBatchDecisionItem i : body.getItems()) {
        items.add(
            new Item(
                i.getProfileId(),
                i.getRatingCode() == null
                    ? null
                    : RatingLevel.valueOf(i.getRatingCode().getValue()),
                i.getReason(),
                i.getRowVersion() == null ? null : i.getRowVersion().longValue(),
                i.getSeenScoreSnapshotId()));
      }
    }
    Command command =
        new Command(
            body.getPeriodId(),
            flow,
            body.getDecisionNo() == null ? null : body.getDecisionNo().strip(),
            body.getDecisionDate(),
            Boolean.TRUE.equals(body.getCalibrationAcknowledged()),
            items);
    String provider = body.getSignProvider() == null ? null : body.getSignProvider().getValue();
    UUID batchId = submission.decide(tenant, actor, command, scope(flow), provider, authorization);
    View view = service.view(tenant, batchId, actor).orElseThrow();
    Job job = BatchDecisionJobs.job(view);
    AppraisalBatchDecisionJob out =
        new AppraisalBatchDecisionJob(
            job.id(), job.type(), JobStatus.fromValue(job.status().name()));
    out.setProgress(job.progress());
    out.setMessage(job.message());
    out.setResultLocation(job.resultLocation());
    out.setUpdatedAt(job.updatedAt());
    out.setBatchId(batchId);
    URI location = Jobs.location(job.id());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(location)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS))
        .body(out);
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getAppraisalBatchDecision",
      module = "appraisals",
      action = "SCR-0904.theo-doi-huy")
  public ResponseEntity<AppraisalBatchDecision> getAppraisalBatchDecision(
      @PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    View view = service.view(tenant, id, actor()).orElseThrow(this::notFound);
    return ETags.ok(dto(tenant, view, new LinkedHashMap<>()), view.batch().rowVersion());
  }

  @PostMapping("/{id}/cancel")
  @Idempotent
  @RequiresPermission(
      operationId = "cancelAppraisalBatchDecision",
      module = "appraisals",
      action = "SCR-0904.theo-doi-huy")
  public ResponseEntity<AppraisalBatchDecision> cancelAppraisalBatchDecision(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    View view;
    try {
      view = service.cancel(tenant, id, expected, actor());
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return ETags.ok(dto(tenant, view, new LinkedHashMap<>()), view.batch().rowVersion());
  }

  // ---------------------------------------------------------------- phạm vi

  /**
   * Phạm vi {@code SCR-0904.ky-lo} của người gọi; không có OPA (môi trường phát triển tắt kiểm
   * quyền) thì chỉ hồ sơ của đơn vị phân công đang chọn.
   */
  private Scope scope(String flow) {
    OpaInput.Subject subject = subject();
    OpaSqlFilter opa = filters.getIfAvailable();
    if (opa == null) {
      return subject.unitId() == null
          ? Scope.NONE
          : new Scope("p.org_unit_id = ?", List.of(UUID.fromString(subject.unitId())));
    }
    ResourceColumnMapping mapping =
        ResourceColumnMapping.builder()
            .ownerUnitColumn("p.org_unit_id")
            .unitClosureTable("org_replica.unit_closure_v")
            .defaultTree("DANG".equals(flow) ? "DANG" : "CHUYEN_MON")
            .uuidIds(true)
            .mapUuid("tenant_id", "p.tenant_id")
            .mapUuid("owner_unit_id", "p.org_unit_id")
            .mapUuid("owner_id", "p.person_id")
            .mapString("flow", "p.flow")
            .mapString("status", "p.status")
            .build();
    try {
      SqlPredicate predicate = opa.buildPredicate(subject, "SCR-0904.ky-lo", mapping);
      return new Scope(predicate.sql(), predicate.params());
    } catch (UntranslatablePolicyException e) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền quyết định các hồ sơ này.");
    }
  }

  // ---------------------------------------------------------------- ánh xạ

  private AppraisalBatchDecision dto(TenantId tenant, View v, Map<UUID, PeriodRef> refs) {
    BatchRow b = v.batch();
    PeriodRef period = refs.computeIfAbsent(b.periodId(), p -> mapper.periodRef(tenant, p));
    AppraisalBatchDecision dto =
        new AppraisalBatchDecision(
            b.id(),
            period,
            Flow.fromValue(b.flow()),
            b.decisionNo(),
            b.decisionDate(),
            new Ref(b.deciderId(), b.deciderName() == null ? "" : b.deciderName()),
            b.profileCount(),
            AppraisalBatchDecisionStatus.fromValue(b.status()),
            (int) b.rowVersion());
    dto.setProfilesHash(HexFormat.of().formatHex(b.profilesHash()));
    dto.setSignRequestId(b.signRequestId());
    dto.setJobId(b.id().toString());
    dto.setSignedCount(v.signedCount());
    return dto;
  }

  private OpaInput.Subject subject() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return subject;
  }

  private UUID actor() {
    try {
      return UUID.fromString(subject().userId());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
