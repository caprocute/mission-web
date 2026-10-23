package vn.viettel.okrkpi.workflow.infrastructure.web.monitor;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.workflow.application.monitor.WorkflowMonitorService;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.api.WorkflowApi;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowDiagram;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInboxItem;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInstancePage;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowTaskExtend;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowTaskReassign;

/**
 * Adapter REST giám sát tiến trình theo mẫu (BS-01 SCR-1506; QT-15): {@code listWorkflowInstances},
 * {@code getWorkflowDiagram}, {@code reassignWorkflowTask}, {@code extendWorkflowTask} của {@code
 * platform.yaml}. Giao diện sinh bằng openapi-generator; không có đường nào sửa biến tiến trình hay
 * chuyển bước. {@code If-Match: *} (RFC 9110) được nhận vì danh sách chưa trả phiên bản của bước
 * (wiki trang 14 mục WEB-062).
 */
@RestController
@RequestMapping("/api/v1")
public class WorkflowMonitorController implements WorkflowApi {

  private static final String MODULE = "WORKFLOW";

  private final WorkflowMonitorService monitor;

  public WorkflowMonitorController(WorkflowMonitorService monitor) {
    this.monitor = monitor;
  }

  @Override
  public ResponseEntity<WorkflowInstancePage> listWorkflowInstances(
      String template, Boolean stuck, String cell, UUID period, Integer limit, String cursor) {
    return ResponseEntity.ok(
        MonitorWebMapper.page(monitor.list(template, stuck, cell, period, limit, cursor)));
  }

  @Override
  public ResponseEntity<WorkflowDiagram> getWorkflowDiagram(String businessKey) {
    return ResponseEntity.ok(MonitorWebMapper.diagram(monitor.diagram(businessKey)));
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<WorkflowInboxItem> reassignWorkflowTask(
      String taskId, String idempotencyKey, String ifMatch, WorkflowTaskReassign body) {
    WorkflowMonitorService.TaskResult result =
        monitor.reassign(
            new WorkflowMonitorService.ReassignCommand(
                taskId, expected(ifMatch), body.getAssigneeId(), body.getReason()));
    return ETags.ok(MonitorWebMapper.item(result), result.projection().rowVersion());
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<WorkflowInboxItem> extendWorkflowTask(
      String taskId, String idempotencyKey, String ifMatch, WorkflowTaskExtend body) {
    WorkflowMonitorService.TaskResult result =
        monitor.extend(
            new WorkflowMonitorService.ExtendCommand(
                taskId, expected(ifMatch), body.getDueAt().toInstant(), body.getReason()));
    return ETags.ok(MonitorWebMapper.item(result), result.projection().rowVersion());
  }

  /** Phiên bản trong {@code If-Match}; {@code *} khớp mọi phiên bản của bước còn mở. */
  static Long expected(String ifMatch) {
    if (ifMatch != null && "*".equals(ifMatch.strip())) {
      return null;
    }
    return ETags.expectedVersion(ifMatch, MODULE);
  }
}
