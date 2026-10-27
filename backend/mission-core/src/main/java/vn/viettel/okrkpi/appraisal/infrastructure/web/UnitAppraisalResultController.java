package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.review.ReviewParticipants;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.unitresult.UnitResultService;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.Filter;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.UnitResultRow;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalProfileStatus;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
import vn.viettel.okrkpi.contract.appraisals.model.Ref;
import vn.viettel.okrkpi.contract.appraisals.model.UnitAppraisalResultPage;
import vn.viettel.okrkpi.contract.appraisals.model.UnitAppraisalResultRow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.OpaSqlFilter;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.authz.ResourceColumnMapping;
import vn.viettel.okrkpi.shared.authz.SqlPredicate;
import vn.viettel.okrkpi.shared.authz.UntranslatablePolicyException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code listUnitAppraisalResults} của {@code appraisals.yaml} (AP-09; SCR-1603 bước đơn vị,
 * HT-39). Vai trò kiểm theo {@code x-mission.roles} của thao tác; phạm vi dòng theo chức năng
 * {@code SCR-1603.xem} của ma trận, OPA dịch sang vị từ SQL trên hồ sơ {@code p} (cây cấp dưới cho
 * {@code leader}, {@code appraiser}, {@code inspector}; đơn vị mình cho {@code manager}).
 */
@RestController
@RequestMapping("/api/v1/appraisals/unit-results")
public class UnitAppraisalResultController {

  static final String ACTION = "SCR-1603.xem";

  private final UnitResultService service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OpaSqlFilter> filters;

  public UnitAppraisalResultController(
      UnitResultService service,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OpaSqlFilter> filters) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.filters = Objects.requireNonNull(filters, "filters");
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listUnitAppraisalResults",
      module = "appraisals",
      resourceType = ReviewParticipants.TYPE)
  public ResponseEntity<UnitAppraisalResultPage> listUnitAppraisalResults(
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "flow", required = false) String flow,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    UUID after = SelfAssessmentController.decodeCursor(cursor);
    Filter filter = new Filter(unit, period, flow, scope(flow), after);
    UnitResultService.Page page = service.listUnitResults(tenant, filter, limit);
    List<UnitAppraisalResultRow> items =
        new ArrayList<>(page.rows().stream().map(UnitAppraisalResultController::row).toList());
    String next =
        page.nextAfter() == null ? null : SelfAssessmentController.encodeCursor(page.nextAfter());
    return ResponseEntity.ok(new UnitAppraisalResultPage(items, next));
  }

  static UnitAppraisalResultRow row(UnitResultRow r) {
    return new UnitAppraisalResultRow(
        r.profileId(),
        new Ref(r.personId(), r.personName() == null ? "" : r.personName()),
        new Ref(r.orgUnitId(), r.orgUnitName() == null ? "" : r.orgUnitName()),
        Flow.fromValue(r.flow()),
        AppraisalProfileStatus.fromValue(r.status()),
        r.snapshotId(),
        r.totalScore(),
        level(r.autoRatingCode()),
        level(r.finalRatingCode()));
  }

  static RatingLevel level(String code) {
    return code == null || code.isEmpty() ? null : RatingLevel.fromValue(code);
  }

  /**
   * Vị từ ABAC của {@link #ACTION}; không có OPA (kiểm thử, cấu hình tối thiểu) thì chỉ đơn vị
   * mình.
   */
  Scope scope(String flow) {
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
      SqlPredicate predicate = opa.buildPredicate(subject, ACTION, mapping);
      return new Scope(predicate.sql(), predicate.params());
    } catch (UntranslatablePolicyException e) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE",
          "Đồng chí không có quyền xem kết quả đánh giá của cán bộ trong đơn vị này.");
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
}
