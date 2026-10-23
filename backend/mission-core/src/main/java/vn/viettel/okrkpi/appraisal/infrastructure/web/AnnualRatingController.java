package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService.Explanation;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.AnnualRow;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.Counts;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.Filter;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.review.ReviewParticipants;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingCounts;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingExplanation;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingPage;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileStatus;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalQuarterRating;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.PeriodRef;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
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
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code listAnnualAppraisalRatings} và {@code getAnnualAppraisalExplanation} của {@code
 * appraisals.yaml} (CORE-04-10, QT-10, SCR-1001). Phạm vi danh sách theo ABAC cây tổ chức như hàng
 * đợi thẩm định ({@code SCR-1001.xem}); giải thích kiểm quyền trên chính hồ sơ năm.
 */
@RestController
@RequestMapping("/api/v1/appraisals/annual")
public class AnnualRatingController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  private final AnnualRatingService service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OpaSqlFilter> filters;
  private final ObjectProvider<AppraisalReviewGuard> guards;

  public AnnualRatingController(
      AnnualRatingService service,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OpaSqlFilter> filters,
      ObjectProvider<AppraisalReviewGuard> guards) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.filters = Objects.requireNonNull(filters, "filters");
    this.guards = Objects.requireNonNull(guards, "guards");
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listAnnualAppraisalRatings",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE)
  public ResponseEntity<AnnualRatingPage> listAnnualAppraisalRatings(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "flow", required = false) String flow,
      @RequestParam(value = "flag", required = false) String flag,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (period == null) {
      throw invalid("period", "Đồng chí chưa chọn kỳ đánh giá năm.");
    }
    if (flow != null && !"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw invalid("flow", "Luồng đánh giá không hợp lệ.");
    }
    if (flag != null && !"lien_thong".equals(flag)) {
      throw invalid("flag", "Bộ lọc cảnh báo không hợp lệ.");
    }
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    TenantId tenant = TenantContext.require();
    Filter filter =
        new Filter(
            period,
            unit,
            flow,
            flag != null,
            q,
            scope(flow),
            SelfAssessmentController.decodeCursor(cursor));
    AnnualRatingService.Page page = service.page(tenant, filter, size + 1);
    boolean more = page.rows().size() > size;
    List<AnnualRow> rows = more ? page.rows().subList(0, size) : page.rows();
    List<AnnualRatingRow> items = rows.stream().map(AnnualRatingController::row).toList();
    String next =
        more ? SelfAssessmentController.encodeCursor(rows.get(rows.size() - 1).id()) : null;
    AnnualRatingPage out = new AnnualRatingPage(new ArrayList<>(items), next);
    Counts c = page.counts();
    out.setCounts(
        new AnnualRatingCounts(
            c.profilesCreated(),
            c.profilesTotal(),
            c.blockedExcellent(),
            c.reassignProposed(),
            c.awaitingProspectGrouping()));
    return ResponseEntity.ok(out);
  }

  @GetMapping("/{id}/explanation")
  @RequiresPermission(
      operationId = "getAnnualAppraisalExplanation",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE)
  public ResponseEntity<AnnualRatingExplanation> getAnnualAppraisalExplanation(
      @PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    Explanation e =
        service
            .explain(
                tenant,
                id,
                actor(),
                r -> AppraisalReviewGuard.canView(guards.getIfAvailable(), participants(tenant, r)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ResponseEntity.ok(explanation(e));
  }

  static AnnualRatingRow row(AnnualRow r) {
    AnnualRatingRow out =
        new AnnualRatingRow(
            r.id(),
            r.profileId(),
            new Ref(r.personId(), r.personName() == null ? "" : r.personName()),
            Flow.fromValue(r.flow()),
            r.quarterRatings().stream().map(AnnualRatingController::level).toList(),
            r.hasKhtnvQuarter(),
            r.consecutiveKhtnv(),
            r.reassignFlag(),
            r.relocationProposed(),
            (int) r.rowVersion());
    out.setOrgUnit(new Ref(r.orgUnitId(), r.orgUnitName() == null ? "" : r.orgUnitName()));
    out.setProfileStatus(AppraisalProfileStatus.fromValue(r.profileStatus()));
    out.setAnnualScore(r.annualScore());
    out.setSelfProposedLevel(level(r.selfProposedLevel()));
    out.setSystemLevel(level(r.systemLevel()));
    out.setRatingCode(level(r.ratingCode()));
    out.setBlockReason(r.blockReason());
    String note = AnnualRatingService.linkageNote(r);
    if (note != null) {
      out.setLinkageNote(note);
    }
    return out;
  }

  static AnnualRatingExplanation explanation(Explanation e) {
    HexFormat hex = HexFormat.of();
    List<AppraisalQuarterRating> quarters = new ArrayList<>();
    for (QuarterRef q : e.quarters()) {
      AppraisalQuarterRating qr =
          new AppraisalQuarterRating(quarterRef(q, e.row().year()), level(q.finalRating()));
      qr.setTotalScore(q.totalScore());
      if (q.policyVersion() != null) {
        qr.setPolicyVersion(q.policyVersion());
      }
      qr.setSnapshotId(q.snapshotId());
      quarters.add(qr);
    }
    AnnualRatingExplanation out =
        new AnnualRatingExplanation(
            e.row().id(),
            quarters,
            e.snapshot() == null
                ? new ArrayList<>()
                : new ArrayList<>(AppraisalScoresController.ruleChecks(e.snapshot().ruleChecks())),
            level(e.systemLevel()));
    out.setAnnualScore(e.row().annualScore());
    if (e.formula() != null) {
      out.setFormula(e.formula());
    }
    if (e.text() != null) {
      out.setText(e.text());
    }
    if (e.legalRef() != null) {
      out.setLegalRef(e.legalRef());
    }
    if (e.snapshot() != null) {
      out.setPolicyVersion(e.snapshot().policyVersion());
      out.setInputHash(hex.formatHex(e.snapshot().inputHash()));
      out.setExceedRatio(e.snapshot().exceedRatio());
    }
    return out;
  }

  /** Kỳ quý của năm: nhãn và ngày suy ra từ năm và số quý (suy luận). */
  static PeriodRef quarterRef(QuarterRef q, int year) {
    LocalDate start = LocalDate.of(year, (q.quarter() - 1) * 3 + 1, 1);
    return new PeriodRef(
        q.periodId(),
        "Quý " + AnnualRatingService.roman(q.quarter()) + "/" + year,
        start,
        start.plusMonths(3).minusDays(1));
  }

  static RatingLevel level(String code) {
    return code == null || code.isEmpty() ? null : RatingLevel.fromValue(code);
  }

  private static ReviewParticipants participants(TenantId tenant, AnnualRow r) {
    return new ReviewParticipants(
        tenant.value(),
        r.profileId(),
        r.orgUnitId(),
        r.flow(),
        r.personId(),
        Set.of(),
        r.appraiserId(),
        r.deciderId());
  }

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
      SqlPredicate predicate = opa.buildPredicate(subject, "SCR-1001.xem", mapping);
      return new Scope(predicate.sql(), predicate.params());
    } catch (UntranslatablePolicyException e) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem danh sách xếp loại năm này.");
    }
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
}
