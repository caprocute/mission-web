package vn.viettel.okrkpi.workflow.infrastructure.web.monitor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import vn.viettel.okrkpi.workflow.application.monitor.WorkflowMonitorService;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.domain.monitor.InstanceSummary;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowDiagram;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInboxItem;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInstancePage;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.model.WorkflowInstanceSummary;

/** Ánh xạ kết quả giám sát sang mô hình sinh từ hợp đồng {@code platform.yaml}. */
final class MonitorWebMapper {

  /** Thời điểm trả về theo giờ Việt Nam như ví dụ của hợp đồng ({@code +07:00}). */
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private MonitorWebMapper() {}

  static WorkflowInstancePage page(WorkflowMonitorService.InstancePage page) {
    WorkflowInstancePage dto = new WorkflowInstancePage();
    dto.setItems(page.items().stream().map(MonitorWebMapper::summary).toList());
    dto.setNextCursor(page.nextCursor());
    return dto;
  }

  static WorkflowInstanceSummary summary(InstanceSummary s) {
    WorkflowInstanceSummary dto = new WorkflowInstanceSummary();
    dto.setBusinessKey(s.businessKey().value());
    dto.setBusinessType(s.businessKey().type());
    dto.setBusinessId(s.businessKey().id());
    dto.setTitle(s.title());
    dto.setTemplateCode(s.templateCode());
    dto.setTemplateVersion(s.templateVersion() == null ? 0 : s.templateVersion());
    dto.setCurrentActivityName(s.currentActivityName());
    dto.setCurrentTaskId(s.currentTaskId());
    dto.setAssigneeId(s.assigneeId());
    dto.setCandidateRole(s.candidateRole());
    dto.setStartedAt(time(s.startedAt()));
    dto.setDueAt(time(s.dueAt()));
    dto.setStuck(s.stuck());
    dto.setStuckReason(s.stuckReason());
    dto.setCellCode(s.cellCode());
    dto.setRowVersion(s.rowVersion());
    return dto;
  }

  static WorkflowDiagram diagram(vn.viettel.okrkpi.workflow.domain.monitor.WorkflowDiagram d) {
    WorkflowDiagram dto = new WorkflowDiagram();
    dto.setBusinessKey(d.businessKey().value());
    dto.setTemplateCode(d.templateCode());
    dto.setTemplateVersion(d.templateVersion());
    dto.setBpmnXml(d.bpmnXml());
    dto.setActiveActivityIds(d.activeActivityIds());
    dto.setCompletedActivityIds(d.completedActivityIds());
    return dto;
  }

  static WorkflowInboxItem item(WorkflowMonitorService.TaskResult result) {
    TaskProjection p = result.projection();
    WorkflowInboxItem dto = new WorkflowInboxItem();
    dto.setTaskId(p.taskId());
    dto.setTitle(p.title());
    dto.setBusinessType(p.businessKey().type());
    dto.setBusinessId(p.businessKey().id());
    dto.setBusinessKey(p.businessKey().value());
    dto.setTemplateCode(p.templateCode());
    dto.setAssigneeId(p.assigneeId());
    dto.setCandidateRole(p.candidateRole());
    dto.setCandidateUnitId(p.candidateUnitId());
    dto.setPeriodId(p.periodId());
    dto.setDueAt(time(p.dueAt()));
    dto.setCreatedAt(time(p.createdAt()));
    dto.setOverdue(p.overdueAt(result.evaluatedAt()));
    dto.setRowVersion(p.rowVersion());
    return dto;
  }

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
  }
}
