package vn.viettel.okrkpi.appraisal.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import vn.viettel.okrkpi.appraisal.application.review.ReviewExportService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewParticipants;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.AppraisalDraft;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.QueueItem;
import vn.viettel.okrkpi.appraisal.application.review.ReviewSubmission;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService.View;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.appraisal.infrastructure.job.ReviewSignJobs;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalExportInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalPeriodKind;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfile;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfilePage;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileStatus;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProposalInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalRatingLevels;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalReturnInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalReviewInput;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
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
 * Thẩm định và đề xuất của {@code appraisals.yaml} (CORE-04-05, QT-09, SCR-0902, SCR-0903): {@code
 * listAppraisalProfiles}, {@code returnAppraisalProfile}, {@code appraiseAppraisalProfile}, {@code
 * proposeAppraisalRating}, {@code exportAppraisalProfiles}. Danh sách và tệp xuất lọc phạm vi bằng
 * đánh giá từng phần của OPA (NEN-014); lệnh ghi hỏi OPA chức năng của ma trận kèm năm vai đã tham
 * gia hồ sơ (BR-09-14).
 */
@RestController
@RequestMapping("/api/v1/appraisals")
public class AppraisalReviewController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;
  static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ReviewService service;
  private final ReviewSubmission submission;
  private final ReviewExportService exports;
  private final SelfAssessmentService profiles;
  private final ObjectProvider<AppraisalReviewGuard> guards;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OpaSqlFilter> filters;
  private final AppraisalProfileMapper mapper;

  public AppraisalReviewController(
      ReviewService service,
      ReviewSubmission submission,
      ReviewExportService exports,
      SelfAssessmentService profiles,
      ObjectProvider<AppraisalReviewGuard> guards,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OpaSqlFilter> filters,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<ObjectMapper> json) {
    this.service = Objects.requireNonNull(service, "service");
    this.submission = Objects.requireNonNull(submission, "submission");
    this.exports = Objects.requireNonNull(exports, "exports");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.guards = Objects.requireNonNull(guards, "guards");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.filters = Objects.requireNonNull(filters, "filters");
    this.mapper = new AppraisalProfileMapper(json.getIfAvailable(ObjectMapper::new), periods);
  }

  @GetMapping("/profiles")
  @RequiresPermission(
      operationId = "listAppraisalProfiles",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE)
  public ResponseEntity<AppraisalProfilePage> listAppraisalProfiles(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "flow", required = false) String flow,
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "status", required = false) List<String> status,
      @RequestParam(value = "includeSubunits", required = false) Boolean includeSubunits,
      @RequestParam(value = "subjectGroup", required = false) UUID subjectGroup,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    QueueFilter filter =
        filter(period, flow, unit, statuses(status), includeSubunits, subjectGroup, q);
    List<QueueItem> rows =
        service.queue(
            tenant,
            filter,
            scope(filter, listAction(filter)),
            SelfAssessmentController.decodeCursor(cursor),
            size + 1);
    boolean more = rows.size() > size;
    List<QueueItem> page = more ? rows.subList(0, size) : rows;
    Map<UUID, PeriodRef> refs = new LinkedHashMap<>();
    List<AppraisalProfileRow> items = new ArrayList<>();
    UUID actor = page.isEmpty() ? null : actor();
    for (QueueItem item : page) {
      ProfileSummary p = item.row().profile();
      PeriodRef ref = refs.computeIfAbsent(p.periodId(), id -> mapper.periodRef(tenant, id));
      AppraisalProfileRow row =
          new AppraisalProfileRow(
              p.id(),
              new Ref(p.personId(), text(item.row().personName())),
              new Ref(p.orgUnitId(), text(item.row().unitName())),
              ref,
              AppraisalPeriodKind.QUY,
              Flow.fromValue(p.flow()),
              AppraisalProfileStatus.fromValue(p.status().name()),
              levels(item.levels()),
              (int) p.rowVersion());
      row.setIsHead(p.head());
      row.setNoSelfAssessment(p.noSelfAssessment());
      row.setAutoRatingCode(AppraisalProfileMapper.rating(p.autoRating()));
      row.setPermittedActions(service.permittedOn(tenant, p, actor));
      items.add(row);
    }
    String next =
        more
            ? SelfAssessmentController.encodeCursor(page.get(page.size() - 1).row().profile().id())
            : null;
    return ResponseEntity.ok(new AppraisalProfilePage(items, next));
  }

  @PostMapping("/profiles/{id}/return")
  @Idempotent
  @RequiresPermission(
      operationId = "returnAppraisalProfile",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE,
      classification = "C2")
  public ResponseEntity<AppraisalProfile> returnAppraisalProfile(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AppraisalReturnInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    UUID actor = actor();
    check(tenant, id, AppraisalReviewGuard::returnProfile);
    try {
      service.returnProfile(tenant, id, expected, actor, body == null ? null : body.getReason());
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return profile(tenant, id, actor);
  }

  @PostMapping("/profiles/{id}/appraisal")
  @Idempotent
  @RequiresPermission(
      operationId = "appraiseAppraisalProfile",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE,
      classification = "C2")
  public ResponseEntity<Job> appraiseAppraisalProfile(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody AppraisalReviewInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    UUID actor = actor();
    check(tenant, id, AppraisalReviewGuard::appraise);
    List<SelfAssessmentForm.Criterion> criteria = new ArrayList<>();
    if (body.getGeneralCriteria() != null) {
      body.getGeneralCriteria()
          .forEach(
              c ->
                  criteria.add(
                      new SelfAssessmentForm.Criterion(
                          c.getCriteriaCode(), Boolean.TRUE.equals(c.getMet()))));
    }
    AppraisalDraft draft =
        new AppraisalDraft(
            level(body.getRatingCode()),
            body.getComment(),
            body.getReason(),
            criteria,
            body.getSeenScoreSnapshotId());
    String provider = body.getSignProvider() == null ? null : body.getSignProvider().getValue();
    String key = idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey;
    UUID signRequestId;
    try {
      signRequestId =
          submission.appraise(tenant, id, expected, actor, draft, provider, key, authorization);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return Jobs.accepted(ReviewSignJobs.pending(signRequestId));
  }

  @PostMapping("/profiles/{id}/proposal")
  @Idempotent
  @RequiresPermission(
      operationId = "proposeAppraisalRating",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE,
      classification = "C2")
  public ResponseEntity<AppraisalProfile> proposeAppraisalRating(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody AppraisalProposalInput body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "APPRAISAL");
    UUID actor = actor();
    check(tenant, id, AppraisalReviewGuard::propose);
    try {
      service.propose(
          tenant,
          id,
          expected,
          actor,
          level(body.getRatingCode()),
          body.getReason(),
          body.getComment(),
          body.getSeenScoreSnapshotId());
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return profile(tenant, id, actor);
  }

  @PostMapping("/exports")
  @Idempotent
  @RequiresPermission(
      operationId = "exportAppraisalProfiles",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE)
  public ResponseEntity<Job> exportAppraisalProfiles(@RequestBody AppraisalExportInput body) {
    TenantId tenant = TenantContext.require();
    if (body == null || body.getPeriodId() == null) {
      throw invalid("periodId", "Đồng chí chưa chọn kỳ đánh giá.");
    }
    List<String> status = new ArrayList<>();
    if (body.getStatus() != null) {
      body.getStatus().forEach(s -> status.add(s.getValue()));
    }
    QueueFilter filter =
        filter(
            body.getPeriodId(),
            body.getFlow() == null ? null : body.getFlow().getValue(),
            body.getUnitId(),
            statuses(status),
            Boolean.TRUE,
            null,
            null);
    String jobId = exports.export(tenant, filter, scope(filter, queueAction(filter)), actor());
    return Jobs.accepted(
        Job.accepted(jobId, "APPRAISAL_EXPORT", OffsetDateTime.now(ZoneOffset.UTC)));
  }

  /**
   * Tải tệp đã xuất ({@code resultLocation} của tác vụ). Hợp đồng chưa có đường dẫn tải riêng của
   * MOD-04 (wiki trang 14); chỉ người tạo tác vụ tải được.
   */
  @GetMapping("/exports/{jobId}")
  @RequiresPermission(
      operationId = "exportAppraisalProfiles",
      module = "appraisals",
      method = "GET",
      resourceType = ReviewParticipants.TYPE)
  public ResponseEntity<byte[]> downloadAppraisalExport(@PathVariable("jobId") String jobId) {
    byte[] file = exports.file(TenantContext.require(), jobId, actor()).orElseThrow(this::notFound);
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("ho-so-danh-gia.xlsx").build().toString())
        .body(file);
  }

  // ---------------------------------------------------------------- kiểm quyền và phạm vi

  /** Hỏi OPA chức năng của ma trận cho hồ sơ; hồ sơ không có thì 404. */
  private void check(
      TenantId tenant, UUID id, BiConsumer<AppraisalReviewGuard, ReviewParticipants> action) {
    ReviewParticipants participants = service.participants(tenant, id).orElseThrow(this::notFound);
    AppraisalReviewGuard guard = guards.getIfAvailable();
    if (guard != null) {
      action.accept(guard, participants);
    }
  }

  /**
   * Phạm vi ABAC của người gọi dịch sang SQL bằng đánh giá từng phần của OPA. Không có OPA (môi
   * trường phát triển tắt kiểm quyền) thì chỉ thấy hồ sơ của đơn vị phân công đang chọn.
   */
  private Scope scope(QueueFilter filter, String action) {
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
            .defaultTree("DANG".equals(filter.flow()) ? "DANG" : "CHUYEN_MON")
            .uuidIds(true)
            .mapUuid("tenant_id", "p.tenant_id")
            .mapUuid("owner_unit_id", "p.org_unit_id")
            .mapUuid("owner_id", "p.person_id")
            .mapString("flow", "p.flow")
            .mapString("status", "p.status")
            .build();
    try {
      SqlPredicate predicate = opa.buildPredicate(subject, action, mapping);
      return new Scope(predicate.sql(), predicate.params());
    } catch (UntranslatablePolicyException e) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem danh sách hồ sơ này.");
    }
  }

  /**
   * Trạng thái hồ sơ đã chốt mà SCR-1301 (tạo yêu cầu mở lại kỳ) được liệt kê: thanh tra xem tên,
   * đơn vị, điểm, mức của các hồ sơ này trong toàn tenant (YC-PT-09, chủ dự án duyệt 28/10/2026).
   */
  static final Set<String> REOPEN_STATUSES =
      Set.of(
          AppraisalStatus.DA_QUYET_DINH.name(),
          AppraisalStatus.DA_KY_SO.name(),
          AppraisalStatus.DA_KHOA.name(),
          AppraisalStatus.KHIEU_NAI.name());

  /**
   * Chức năng của ma trận cho danh sách {@code listAppraisalProfiles}: bộ lọc chỉ gồm trạng thái đã
   * chốt là SCR-1301.tao (appraiser giữ phạm vi Cd, inspector toàn tenant); còn lại như {@link
   * #queueAction}. Xuất tệp không đi qua nhánh SCR-1301.
   */
  static String listAction(QueueFilter filter) {
    if (filter.statuses() != null
        && !filter.statuses().isEmpty()
        && REOPEN_STATUSES.containsAll(filter.statuses())) {
      return "SCR-1301.tao";
    }
    return queueAction(filter);
  }

  /**
   * Chức năng xem của ma trận theo màn hình: chờ quyết định là SCR-0904, đã thẩm định chờ đề xuất
   * là SCR-0903, còn lại là hàng đợi thẩm định SCR-0902.
   */
  static String queueAction(QueueFilter filter) {
    if (filter.statuses().equals(Set.of(AppraisalStatus.CHO_QUYET_DINH.name()))) {
      return "SCR-0904.xem";
    }
    if (filter.statuses().equals(Set.of(AppraisalStatus.DA_THAM_DINH.name()))) {
      return "SCR-0903.xem";
    }
    return "SCR-0902.xem";
  }

  private static QueueFilter filter(
      UUID period,
      String flow,
      UUID unit,
      Set<String> statuses,
      Boolean includeSubunits,
      UUID subjectGroup,
      String q) {
    if (flow != null && !"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw invalid("flow", "Luồng đánh giá không hợp lệ.");
    }
    return new QueueFilter(
        period, flow, unit, includeSubunits == null || includeSubunits, statuses, subjectGroup, q);
  }

  /** Mặc định hàng đợi thẩm định ({@code CHO_THAM_DINH}). */
  static Set<String> statuses(List<String> status) {
    Set<String> out = new LinkedHashSet<>();
    if (status != null) {
      for (String s : status) {
        try {
          out.add(AppraisalStatus.valueOf(s).name());
        } catch (IllegalArgumentException e) {
          throw invalid("status", "Trạng thái hồ sơ không hợp lệ.");
        }
      }
    }
    if (out.isEmpty()) {
      out.add(AppraisalStatus.CHO_THAM_DINH.name());
    }
    return out;
  }

  // ---------------------------------------------------------------- ánh xạ

  private ResponseEntity<AppraisalProfile> profile(TenantId tenant, UUID id, UUID actor) {
    View view = profiles.view(tenant, id, actor, p -> true).orElseThrow(this::notFound);
    AppraisalProfile dto = mapper.profile(tenant, view);
    dto.setPermittedActions(service.permittedOn(tenant, view.profile(), actor));
    return ETags.ok(dto, view.profile().rowVersion());
  }

  private static AppraisalRatingLevels levels(Map<LevelKind, RatingLevel> levels) {
    AppraisalRatingLevels dto = new AppraisalRatingLevels();
    dto.setSelfProposed(AppraisalProfileMapper.rating(levels.get(LevelKind.TU_DE_XUAT)));
    dto.setAppraised(AppraisalProfileMapper.rating(levels.get(LevelKind.THAM_DINH)));
    dto.setProposed(AppraisalProfileMapper.rating(levels.get(LevelKind.DE_XUAT)));
    dto.setDecided(AppraisalProfileMapper.rating(levels.get(LevelKind.QUYET_DINH)));
    return dto;
  }

  private static RatingLevel level(vn.viettel.okrkpi.contract.appraisals.model.RatingLevel code) {
    return code == null ? null : RatingLevel.valueOf(code.getValue());
  }

  private static String text(String s) {
    return s == null ? "" : s;
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
