package vn.viettel.okrkpi.appraisal.infrastructure.web;

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
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInputGeneralCriteriaInner;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInputPage;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code listAppraisalScoringInputs} của {@code appraisals.yaml} (CORE-04-06, QT-09): chỉ tài khoản
 * dịch vụ của SVC-07 ({@code serviceClient}) đọc được. Trường mở rộng {@code workItems}, {@code
 * responsibilityScope}, {@code incidents} của BS-03 để trống (CORE-04-13).
 */
@RestController
@RequestMapping("/api/v1/appraisals/inputs")
public class AppraisalScoringInputController {

  static final int DEFAULT_LIMIT = 50;
  static final String SERVICE_CLIENT = "service_client";

  private final ScoringInputService service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;

  public AppraisalScoringInputController(
      ScoringInputService service, ObjectProvider<AuthzSubjectResolver> subjects) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
  }

  @GetMapping
  public ResponseEntity<AppraisalScoringInputPage> listAppraisalScoringInputs(
      @RequestParam("period") UUID period,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    requireServiceClient();
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > ScoringInputService.MAX_LIMIT) {
      String message = "Số dòng mỗi trang từ 1 đến 200.";
      throw new BusinessException(
          "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError("limit", message, null)));
    }
    List<ScoringInput> rows =
        service.page(tenant, period, SelfAssessmentController.decodeCursor(cursor), size + 1);
    boolean more = rows.size() > size;
    List<ScoringInput> page = more ? rows.subList(0, size) : rows;
    List<AppraisalScoringInput> items =
        page.stream().map(AppraisalScoringInputController::dto).toList();
    String next =
        more ? SelfAssessmentController.encodeCursor(page.get(page.size() - 1).profileId()) : null;
    return ResponseEntity.ok(new AppraisalScoringInputPage(items, next));
  }

  static AppraisalScoringInput dto(ScoringInput in) {
    AppraisalScoringInput dto =
        new AppraisalScoringInput(
            in.profileId(),
            in.personId(),
            Flow.fromValue(in.flow()),
            in.orgUnitId(),
            in.policyPackVersionId());
    dto.setSubjectGroupId(in.subjectGroupId());
    dto.setIsHead(in.head());
    dto.setIsLeadership(in.leadership());
    dto.setGeneralCriteria(
        in.generalCriteria().stream()
            .map(c -> new AppraisalScoringInputGeneralCriteriaInner(c.code(), c.met()))
            .toList());
    dto.setWorkItems(null);
    dto.setResponsibilityScope(null);
    dto.setIncidents(null);
    return dto;
  }

  /** Token client credentials hoặc vai trò {@code service_client}; người dùng thường bị 403. */
  private void requireServiceClient() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    boolean client =
        "client".equals(subject.kind())
            || (subject.roles() != null && subject.roles().contains(SERVICE_CLIENT));
    if (!client) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Chỉ dịch vụ tính điểm của hệ thống được đọc đầu vào");
    }
  }
}
