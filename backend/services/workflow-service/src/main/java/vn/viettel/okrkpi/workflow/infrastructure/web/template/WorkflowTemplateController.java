package vn.viettel.okrkpi.workflow.infrastructure.web.template;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.application.template.TemplateErrors;
import vn.viettel.okrkpi.workflow.application.template.TemplateTestService;
import vn.viettel.okrkpi.workflow.application.template.TemplateView;
import vn.viettel.okrkpi.workflow.application.template.WorkflowTemplateService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.template.CellRollout;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;
import vn.viettel.okrkpi.workflow.infrastructure.release.TemplateTestJobSource;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaJobSource;
import vn.viettel.okrkpi.workflow.infrastructure.web.CurrentCaller;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.api.WorkflowApi;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.ApprovalComment;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.ApproveTenantMove202Response;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplate;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplateCreate;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplatePage;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplateRolloutList;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplateRolloutListItemsInner;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplateStatus;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplateSummary;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.model.WorkflowTemplateUpdate;

/**
 * API thư viện và vòng đời mẫu luồng nội bộ {@code /api/v1/workflow/templates} (BS-01 SCR-1505,
 * QT-15; DV-06-09). Quyền theo ma trận SCR-1505 qua OPA ở tầng ứng dụng; bốn mắt kiểm ở miền trước
 * rồi ở OPA.
 */
@RestController
@RequestMapping("/api/v1")
public class WorkflowTemplateController implements WorkflowApi {

  private static final String CURSOR_SCOPE = "wf-template-library";

  private final WorkflowTemplateService templates;
  private final TemplateTestService tests;
  private final CursorCodec cursors;

  public WorkflowTemplateController(
      WorkflowTemplateService templates, TemplateTestService tests, CursorCodec cursors) {
    this.templates = templates;
    this.tests = tests;
    this.cursors = cursors;
  }

  @Override
  public ResponseEntity<WorkflowTemplatePage> listWorkflowTemplates(
      WorkflowTemplateStatus status, String q, Integer limit, String cursor) {
    String scope = CURSOR_SCOPE + ":" + (status == null ? "" : status.getValue()) + ":" + q;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    String after = page.afterKey().map(k -> k.getString(0)).orElse(null);
    List<TemplateView> fetched =
        templates.list(
            status == null ? null : TemplateStatus.valueOf(status.getValue()),
            q,
            after,
            page.fetchSize());
    CursorPage<TemplateView> result =
        cursors.page(
            fetched, page, v -> CursorKey.of(WorkflowTemplateStore.cursorKey(v.template())));
    WorkflowTemplatePage body =
        new WorkflowTemplatePage(
            result.items().stream().map(WorkflowTemplateController::summary).toList(),
            result.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<WorkflowTemplate> createWorkflowTemplate(
      String idempotencyKey, WorkflowTemplateCreate body) {
    TemplateView created =
        templates.create(
            new WorkflowTemplateService.CreateCommand(
                TenantContext.require().value(),
                body.getTemplateCode(),
                body.getBasedOnTemplateId(),
                body.getBpmnXml(),
                schema(body.getParamSchema()),
                CurrentCaller.actor()));
    HttpHeaders headers = ETags.headers(created.template().rowVersion());
    headers.setLocation(URI.create("/api/v1/workflow/templates/" + created.template().id()));
    return ResponseEntity.status(HttpStatus.CREATED).headers(headers).body(full(created));
  }

  @Override
  public ResponseEntity<WorkflowTemplate> getWorkflowTemplate(UUID id) {
    TemplateView view = templates.get(id);
    return ETags.ok(full(view), view.template().rowVersion());
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<WorkflowTemplate> updateWorkflowTemplate(
      UUID id, String idempotencyKey, String ifMatch, WorkflowTemplateUpdate body) {
    long expected = ETags.expectedVersion(ifMatch, TemplateErrors.MODULE);
    TemplateView saved =
        templates.update(
            new WorkflowTemplateService.UpdateCommand(
                id,
                body.getBpmnXml(),
                schema(body.getParamSchema()),
                expected,
                CurrentCaller.actor()));
    return ETags.ok(full(saved), saved.template().rowVersion());
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<ApproveTenantMove202Response> runWorkflowTemplateTests(
      UUID id, String idempotencyKey) {
    Job job = TemplateTestJobSource.job(tests.request(id, CurrentCaller.actor()));
    return accepted(job);
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<WorkflowTemplate> submitWorkflowTemplate(
      UUID id, String idempotencyKey, String ifMatch, ApprovalComment comment) {
    long expected = ETags.expectedVersion(ifMatch, TemplateErrors.MODULE);
    TemplateView saved = templates.submit(id, expected, CurrentCaller.actor());
    return ETags.ok(full(saved), saved.template().rowVersion());
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<ApproveTenantMove202Response> approveWorkflowTemplate(
      UUID id, String idempotencyKey, String ifMatch, ApprovalComment comment) {
    long expected = ETags.expectedVersion(ifMatch, TemplateErrors.MODULE);
    SagaInstance saga =
        templates.approve(id, expected, CurrentCaller.actor(), TenantContext.require().value());
    return accepted(SagaJobSource.job(saga));
  }

  @Override
  public ResponseEntity<WorkflowTemplateRolloutList> listWorkflowTemplateRollouts(UUID id) {
    List<WorkflowTemplateRolloutListItemsInner> items =
        templates.rollouts(id).stream().map(WorkflowTemplateController::rollout).toList();
    return ResponseEntity.ok(new WorkflowTemplateRolloutList(items));
  }

  // --- Ánh xạ ------------------------------------------------------------------------------

  private static ResponseEntity<ApproveTenantMove202Response> accepted(Job job) {
    ApproveTenantMove202Response dto =
        new ApproveTenantMove202Response(
            job.id(),
            job.type(),
            ApproveTenantMove202Response.StatusEnum.fromValue(job.status().name()));
    dto.setProgress(job.progress());
    dto.setMessage(job.message());
    dto.setUpdatedAt(job.updatedAt());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(Jobs.location(job.id()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS))
        .body(dto);
  }

  static WorkflowTemplateSummary summary(TemplateView v) {
    TemplateVersion t = v.template();
    WorkflowTemplateSummary dto =
        new WorkflowTemplateSummary(
            t.id(),
            t.templateCode(),
            t.versionNo(),
            WorkflowTemplateStatus.fromValue(t.status().name()),
            (int) t.rowVersion());
    dto.setBpmnSha256(t.sha256Hex());
    dto.setTenantBindingCount(v.tenantBindingCount());
    dto.setTestCount(v.testCount());
    dto.setUpdatedAt(t.updatedAt() == null ? null : t.updatedAt().atOffset(ZoneOffset.UTC));
    return dto;
  }

  static WorkflowTemplate full(TemplateView v) {
    TemplateVersion t = v.template();
    WorkflowTemplate dto =
        new WorkflowTemplate(
            t.id(),
            t.templateCode(),
            t.versionNo(),
            WorkflowTemplateStatus.fromValue(t.status().name()),
            (int) t.rowVersion());
    dto.setBpmnSha256(t.sha256Hex());
    dto.setTenantBindingCount(v.tenantBindingCount());
    dto.setTestCount(v.testCount());
    dto.setUpdatedAt(t.updatedAt() == null ? null : t.updatedAt().atOffset(ZoneOffset.UTC));
    dto.setBpmnXml(t.bpmnXml());
    dto.setParamSchema(t.paramSchema());
    dto.setTestSuiteRef(t.testSuiteRef());
    dto.setPolicyPackVersionId(t.policyPackVersionId());
    dto.setFlowableDeploymentId(t.flowableDeploymentId());
    dto.setApprovedBy(t.approvedBy());
    dto.setMergeRequestUrl(v.mergeRequestUrl() == null ? null : URI.create(v.mergeRequestUrl()));
    return dto;
  }

  static WorkflowTemplateRolloutListItemsInner rollout(CellRollout r) {
    WorkflowTemplateRolloutListItemsInner dto =
        new WorkflowTemplateRolloutListItemsInner(
            r.cellCode(),
            WorkflowTemplateRolloutListItemsInner.StatusEnum.fromValue(r.status().name()));
    dto.setFlowableDeploymentId(r.deploymentId());
    dto.setDeployedAt(r.deployedAt() == null ? null : r.deployedAt().atOffset(ZoneOffset.UTC));
    return dto;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schema(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> copy = new LinkedHashMap<>();
      ((Map<Object, Object>) m).forEach((k, val) -> copy.put(String.valueOf(k), val));
      return copy;
    }
    throw TemplateErrors.invalid(
        "paramSchema", "Khai báo tham số phải là một đối tượng JSON Schema.", "type");
  }
}
