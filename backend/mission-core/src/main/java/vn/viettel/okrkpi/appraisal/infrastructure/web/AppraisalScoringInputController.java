package vn.viettel.okrkpi.appraisal.infrastructure.web;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Extensions;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Responsibility;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.WorkItemPage;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalIncidentInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInput;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInputGeneralCriteriaInner;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoringInputPage;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalWorkItemPage;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.QualityLevel;
import vn.viettel.okrkpi.contract.appraisals.model.ResponsibilityItem;
import vn.viettel.okrkpi.contract.appraisals.model.WorkItem;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;

/**
 * {@code listAppraisalScoringInputs} của {@code appraisals.yaml} (CORE-04-06, QT-09): chỉ tài khoản
 * dịch vụ của SVC-07 ({@code serviceClient}) đọc được. Trường mở rộng {@code workItems}, {@code
 * responsibilityScope}, {@code incidents} của BS-03 chỉ có khi gói ghim của hồ sơ khai báo
 * (CORE-04-13); việc lá vượt 200 đọc tiếp ở {@code listAppraisalWorkItems}.
 */
@RestController
@RequestMapping("/api/v1/appraisals/inputs")
public class AppraisalScoringInputController {

  static final int DEFAULT_LIMIT = 50;
  static final String SERVICE_CLIENT = "service_client";

  private final ScoringInputService service;
  private final ScoringExtensionService extensions;
  private final ObjectProvider<AuthzSubjectResolver> subjects;

  public AppraisalScoringInputController(
      ScoringInputService service, ObjectProvider<AuthzSubjectResolver> subjects) {
    this(service, null, subjects);
  }

  @Autowired
  public AppraisalScoringInputController(
      ScoringInputService service,
      ScoringExtensionService extensions,
      ObjectProvider<AuthzSubjectResolver> subjects) {
    this.service = Objects.requireNonNull(service, "service");
    this.extensions = extensions;
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
    Map<UUID, Extensions> extra =
        extensions == null ? Map.of() : extensions.extend(tenant, period, page);
    List<AppraisalScoringInput> items =
        page.stream()
            .map(in -> dto(in, extra.getOrDefault(in.profileId(), Extensions.NONE)))
            .toList();
    String next =
        more ? SelfAssessmentController.encodeCursor(page.get(page.size() - 1).profileId()) : null;
    return ResponseEntity.ok(new AppraisalScoringInputPage(items, next));
  }

  @GetMapping("/{profileId}/work-items")
  public ResponseEntity<AppraisalWorkItemPage> listAppraisalWorkItems(
      @PathVariable("profileId") UUID profileId,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    requireServiceClient();
    TenantId tenant = TenantContext.require();
    int size = limit == null ? ScoringExtensionService.WORK_ITEM_LIMIT : limit;
    if (size < 1 || size > ScoringExtensionService.WORK_ITEM_LIMIT) {
      String message = "Số dòng mỗi trang từ 1 đến 200.";
      throw new BusinessException(
          "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError("limit", message, null)));
    }
    if (extensions == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    WorkItemPage page;
    try {
      page =
          extensions.workItems(
              tenant, profileId, SelfAssessmentController.decodeCursor(cursor), size);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok(
        new AppraisalWorkItemPage(
            page.items().stream().map(AppraisalScoringInputController::workItem).toList(),
            page.nextAfter() == null
                ? null
                : SelfAssessmentController.encodeCursor(page.nextAfter())));
  }

  static AppraisalScoringInput dto(ScoringInput in) {
    return dto(in, Extensions.NONE);
  }

  static AppraisalScoringInput dto(ScoringInput in, Extensions ext) {
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
    if (ext.workItems() != null) {
      dto.setWorkItems(
          ext.workItems().stream().map(AppraisalScoringInputController::workItem).toList());
      dto.setWorkItemsNextCursor(
          ext.workItemsAfter() == null
              ? null
              : SelfAssessmentController.encodeCursor(ext.workItemsAfter()));
    } else {
      dto.setWorkItems(null);
    }
    dto.setResponsibilityScope(
        ext.responsibility() == null
            ? null
            : ext.responsibility().stream()
                .map(AppraisalScoringInputController::responsibility)
                .toList());
    dto.setIncidents(
        ext.incidents() == null
            ? null
            : ext.incidents().stream().map(AppraisalScoringInputController::incident).toList());
    return dto;
  }

  static WorkItem workItem(WorkItemSnapshot w) {
    WorkItem item =
        new WorkItem(
            w.taskId(),
            w.standardScore(),
            new java.util.HashMap<>(w.coefficientLevels()),
            WorkItem.StatusAtLockEnum.fromValue(w.statusAtLock()));
    item.setParentTaskId(w.parentTaskId());
    item.setDifficulty(w.difficulty());
    item.setTaskGroup(
        w.taskGroup() == null ? null : WorkItem.TaskGroupEnum.fromValue(w.taskGroup()));
    item.setAxisCode(w.axisCode());
    item.setComponentScore(w.componentScore());
    item.setQualityLevel(
        w.qualityLevel() == null ? null : QualityLevel.fromValue(w.qualityLevel()));
    item.setSubjectiveLateWorkdays(w.subjectiveLateWorkdays());
    item.setObjectiveLateWorkdays(w.objectiveLateWorkdays());
    item.setExceeded(w.exceeded());
    item.setOutOfScope(w.outOfScope());
    item.setDeferredByDecision(w.deferredByDecision());
    return item;
  }

  static ResponsibilityItem responsibility(Responsibility r) {
    ResponsibilityItem item =
        new ResponsibilityItem(ResponsibilityItem.ItemTypeEnum.fromValue(r.itemType()), r.itemId());
    item.setSumS(r.sumS());
    item.setSumQ(r.sumQ());
    item.setSumD(r.sumD());
    item.setTargetAchievedRatio(r.targetAchievedRatio());
    item.setConfirmedLevel(
        r.confirmedLevel() == null ? null : QualityLevel.fromValue(r.confirmedLevel()));
    item.setOwnLateWorkdays(r.ownLateWorkdays());
    return item;
  }

  static AppraisalIncidentInput incident(IncidentRow r) {
    AppraisalIncidentInput in =
        new AppraisalIncidentInput(r.id(), r.incidentType(), r.concludedOn());
    in.setImpact(r.impact());
    return in;
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
