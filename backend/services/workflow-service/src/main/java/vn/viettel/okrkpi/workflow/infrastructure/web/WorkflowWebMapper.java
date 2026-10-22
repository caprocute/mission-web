package vn.viettel.okrkpi.workflow.infrastructure.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.workflow.application.CompleteTaskService;
import vn.viettel.okrkpi.workflow.application.InboxService;
import vn.viettel.okrkpi.workflow.domain.ProcessHistory;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.ApproveTenantMove202Response;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowDecision;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowHistory;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowHistoryAction;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowHistoryEntriesInner;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowInboxItem;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowInboxPage;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowInboxPageCounts;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowTaskResult;

/** Ánh xạ kết quả use case sang mô hình sinh từ hợp đồng {@code platform.yaml}. */
final class WorkflowWebMapper {

  /** Thời điểm trả về theo giờ Việt Nam như ví dụ của hợp đồng ({@code +07:00}). */
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private WorkflowWebMapper() {}

  static WorkflowInboxPage inbox(InboxService.InboxPage page) {
    WorkflowInboxPage dto = new WorkflowInboxPage();
    dto.setItems(page.items().stream().map(p -> item(p, page.evaluatedAt())).toList());
    dto.setNextCursor(page.nextCursor());
    WorkflowInboxPageCounts counts = new WorkflowInboxPageCounts();
    counts.setTotal(page.counts().total());
    counts.setOverdue(page.counts().overdue());
    counts.setDueToday(page.counts().dueToday());
    dto.setCounts(counts);
    return dto;
  }

  static WorkflowInboxItem item(TaskProjection p, Instant now) {
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
    dto.setOverdue(p.overdueAt(now));
    dto.setRowVersion(p.rowVersion());
    return dto;
  }

  static WorkflowTaskResult result(CompleteTaskService.Result r) {
    WorkflowTaskResult dto = new WorkflowTaskResult();
    dto.setTaskId(r.taskId());
    dto.setBusinessKey(r.businessKey().value());
    dto.setDecision(WorkflowDecision.fromValue(r.decision().name()));
    dto.setCompletedAt(time(r.completedAt()));
    dto.setProcessEnded(r.processEnded());
    return dto;
  }

  static WorkflowHistory history(ProcessHistory h) {
    WorkflowHistory dto = new WorkflowHistory();
    dto.setBusinessKey(h.businessKey().value());
    dto.setBusinessType(h.businessKey().type());
    dto.setTemplateCode(h.templateCode());
    dto.setTemplateVersion(h.templateVersion());
    dto.setStatus(
        h.ended() ? WorkflowHistory.StatusEnum.DA_KET_THUC : WorkflowHistory.StatusEnum.DANG_CHAY);
    dto.setEntries(
        h.entries().stream()
            .map(
                e -> {
                  WorkflowHistoryEntriesInner entry = new WorkflowHistoryEntriesInner();
                  entry.setAt(time(e.at()));
                  entry.setAction(WorkflowHistoryAction.fromValue(e.action().name()));
                  entry.setActorId(e.actorId());
                  entry.setActivityName(e.activityName());
                  entry.setComment(e.comment());
                  return entry;
                })
            .toList());
    return dto;
  }

  static ApproveTenantMove202Response job(Job job) {
    ApproveTenantMove202Response dto = new ApproveTenantMove202Response();
    dto.setId(job.id());
    dto.setType(job.type());
    dto.setStatus(ApproveTenantMove202Response.StatusEnum.fromValue(job.status().name()));
    dto.setProgress(job.progress());
    dto.setMessage(job.message());
    dto.setResultLocation(job.resultLocation());
    dto.setUpdatedAt(
        job.updatedAt() == null
            ? null
            : job.updatedAt().atZoneSameInstant(ZONE).toOffsetDateTime());
    return dto;
  }

  private static OffsetDateTime time(Instant value) {
    return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
  }
}
