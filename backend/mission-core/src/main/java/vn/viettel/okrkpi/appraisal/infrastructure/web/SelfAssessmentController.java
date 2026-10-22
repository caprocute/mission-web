package vn.viettel.okrkpi.appraisal.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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
import vn.viettel.okrkpi.appraisal.application.review.ReviewParticipants;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService.View;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentSubmission;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalPeriodKind;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfile;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfilePage;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileStatus;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalRatingLevels;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalSelfAssessmentInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalSignRequestInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalTaskClaim;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.PeriodRef;
import vn.viettel.okrkpi.contract.appraisals.model.Ref;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Phiếu tự đánh giá của {@code appraisals.yaml} (CORE-04-04, QT-09, SCR-0901): {@code
 * listMyAppraisalProfiles}, {@code getAppraisalProfile}, {@code saveAppraisalSelfAssessment},
 * {@code submitAppraisalSelfAssessment}. Tên người, đơn vị chưa có bản sao trong MOD-04 nên trả
 * rỗng; loại kỳ mặc định {@code QUY} vì hồ sơ năm nằm ở {@code annual_rating} (suy luận).
 */
@RestController
@RequestMapping("/api/v1/appraisals/profiles")
public class SelfAssessmentController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;

  private final SelfAssessmentService service;
  private final SelfAssessmentSubmission submission;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PeriodApi> periods;
  private final ObjectProvider<AppraisalReviewGuard> guards;
  private final ObjectMapper json;
  private final AppraisalProfileMapper mapper;

  public SelfAssessmentController(
      SelfAssessmentService service,
      SelfAssessmentSubmission submission,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<AppraisalReviewGuard> guards) {
    this.service = Objects.requireNonNull(service, "service");
    this.submission = Objects.requireNonNull(submission, "submission");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.guards = Objects.requireNonNull(guards, "guards");
    this.json = json.getIfAvailable(ObjectMapper::new);
    this.mapper = new AppraisalProfileMapper(this.json, periods);
  }

  @GetMapping("/mine")
  @RequiresPermission(
      operationId = "listMyAppraisalProfiles",
      module = "appraisals",
      resourceType = "appraisal_profile")
  public ResponseEntity<AppraisalProfilePage> listMyAppraisalProfiles(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "flow", required = false) String flow,
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
    List<ProfileSummary> rows =
        service.mine(tenant, actor(), period, flow, decodeCursor(cursor), size + 1);
    boolean more = rows.size() > size;
    List<ProfileSummary> page = more ? rows.subList(0, size) : rows;
    Map<UUID, PeriodRef> refs = new LinkedHashMap<>();
    List<AppraisalProfileRow> items = new ArrayList<>();
    for (ProfileSummary p : page) {
      PeriodRef ref = refs.computeIfAbsent(p.periodId(), id -> mapper.periodRef(tenant, id));
      AppraisalRatingLevels levels = new AppraisalRatingLevels();
      AppraisalProfileRow row =
          new AppraisalProfileRow(
              p.id(),
              new Ref(p.personId(), ""),
              new Ref(p.orgUnitId(), ""),
              ref,
              AppraisalPeriodKind.QUY,
              Flow.fromValue(p.flow()),
              AppraisalProfileStatus.fromValue(p.status().name()),
              levels,
              (int) p.rowVersion());
      row.setIsHead(p.head());
      row.setNoSelfAssessment(p.noSelfAssessment());
      if (p.autoRating() != null) {
        row.setAutoRatingCode(AppraisalProfileMapper.rating(p.autoRating()));
      }
      items.add(row);
    }
    String next = more ? encodeCursor(page.get(page.size() - 1).id()) : null;
    return ResponseEntity.ok(new AppraisalProfilePage(items, next));
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getAppraisalProfile",
      module = "appraisals",
      resourceType = "appraisal_profile",
      classification = "C2")
  public ResponseEntity<AppraisalProfile> getAppraisalProfile(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    View view =
        service
            .view(
                tenant,
                id,
                actor(),
                p -> AppraisalReviewGuard.canView(guards.getIfAvailable(), participants(tenant, p)))
            .orElseThrow(SelfAssessmentController::notFound);
    return ETags.ok(mapper.profile(tenant, view), view.profile().rowVersion());
  }

  @PutMapping("/{id}/self-assessment")
  @Idempotent
  @RequiresPermission(
      operationId = "saveAppraisalSelfAssessment",
      module = "appraisals",
      resourceType = "appraisal_profile",
      classification = "C2")
  public ResponseEntity<AppraisalProfile> saveAppraisalSelfAssessment(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AppraisalSelfAssessmentInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    View view;
    try {
      view = service.save(tenant, id, expected, actor(), form(body));
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return ETags.ok(mapper.profile(tenant, view), view.profile().rowVersion());
  }

  @PostMapping("/{id}/submit")
  @Idempotent
  @RequiresPermission(
      operationId = "submitAppraisalSelfAssessment",
      module = "appraisals",
      resourceType = "appraisal_profile")
  public ResponseEntity<Job> submitAppraisalSelfAssessment(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) AppraisalSignRequestInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    String provider =
        body == null || body.getSignProvider() == null ? null : body.getSignProvider().getValue();
    byte[] clientHash = null;
    if (body != null && body.getContentHash() != null) {
      try {
        clientHash = HexFormat.of().parseHex(body.getContentHash());
      } catch (IllegalArgumentException e) {
        throw invalid("contentHash", "Mã băm phiếu không hợp lệ.");
      }
    }
    String key = idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey;
    UUID signRequestId;
    try {
      signRequestId =
          submission.submit(
              tenant, id, expected, actor(), provider, clientHash, key, authorization);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return Jobs.accepted(SelfAssessmentJobs.pending(signRequestId));
  }

  // ---------------------------------------------------------------- ánh xạ

  private SelfAssessmentForm form(AppraisalSelfAssessmentInput in) {
    if (in == null) {
      return SelfAssessmentForm.empty();
    }
    List<SelfAssessmentForm.Criterion> criteria = new ArrayList<>();
    if (in.getCriteria() != null) {
      in.getCriteria()
          .forEach(
              c ->
                  criteria.add(
                      new SelfAssessmentForm.Criterion(
                          c.getCriteriaCode(), Boolean.TRUE.equals(c.getMet()))));
    }
    List<Map<String, Object>> claims = new ArrayList<>();
    if (in.getClaims() != null) {
      for (AppraisalTaskClaim c : in.getClaims()) {
        claims.add(json.convertValue(c, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
      }
    }
    return new SelfAssessmentForm(
        criteria,
        in.getSelfRatingCode() == null
            ? null
            : RatingLevel.valueOf(in.getSelfRatingCode().getValue()),
        in.getBreakthrough(),
        in.getBreakthroughEvidenceIds() == null
            ? List.of()
            : List.copyOf(in.getBreakthroughEvidenceIds()),
        in.getReason(),
        in.getContent(),
        in.getStrengths(),
        in.getWeaknesses(),
        in.getRemedialPlan(),
        claims);
  }

  /** Tài nguyên OPA của hồ sơ để kiểm quyền đọc theo phạm vi (không cần nhóm người nghiệm thu). */
  private static ReviewParticipants participants(TenantId tenant, ProfileSummary p) {
    return new ReviewParticipants(
        tenant.value(),
        p.id(),
        p.orgUnitId(),
        p.flow(),
        p.personId(),
        Set.of(),
        p.appraiserId(),
        p.deciderId());
  }

  private UUID actor() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    try {
      return UUID.fromString(subject.userId());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }

  static String encodeCursor(UUID last) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(last.toString().getBytes(StandardCharsets.US_ASCII));
  }

  static UUID decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException e) {
      throw invalid("cursor", "Con trỏ trang không hợp lệ.");
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
