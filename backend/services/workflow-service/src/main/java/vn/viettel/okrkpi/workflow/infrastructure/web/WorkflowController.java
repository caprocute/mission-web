package vn.viettel.okrkpi.workflow.infrastructure.web;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.workflow.application.BatchCompleteService;
import vn.viettel.okrkpi.workflow.application.CompleteTaskService;
import vn.viettel.okrkpi.workflow.application.HistoryQueryService;
import vn.viettel.okrkpi.workflow.application.InboxService;
import vn.viettel.okrkpi.workflow.domain.BatchJob;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.infrastructure.batch.WorkflowBatchJobSource;
import vn.viettel.okrkpi.workflow.infrastructure.web.api.WorkflowApi;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.ApproveTenantMove202Response;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowBatchCompletion;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowHistory;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowInboxPage;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowTaskCompletion;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowTaskResult;

/**
 * Adapter REST của tag Workflow trong {@code platform.yaml} (DV-06-02; QT-02, QT-04, QT-09;
 * SCR-0203): hộp việc, hoàn thành, duyệt hàng loạt, lịch sử. Giao diện sinh bằng openapi-generator
 * ({@code interfaceOnly}); controller chỉ đổi kiểu, nghiệp vụ nằm ở tầng {@code application}.
 */
@RestController
@RequestMapping("/api/v1")
public class WorkflowController implements WorkflowApi {

  private static final String MODULE = "WORKFLOW";

  private final InboxService inbox;
  private final CompleteTaskService completer;
  private final BatchCompleteService batches;
  private final HistoryQueryService histories;
  private final Clock clock;

  public WorkflowController(
      InboxService inbox,
      CompleteTaskService completer,
      BatchCompleteService batches,
      HistoryQueryService histories,
      Clock clock) {
    this.inbox = inbox;
    this.completer = completer;
    this.batches = batches;
    this.histories = histories;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<WorkflowInboxPage> listWorkflowInbox(
      String assignee, String businessType, UUID period, Integer limit, String cursor) {
    if (assignee != null && !"me".equals(assignee)) {
      throw new BusinessException("COMMON-422-VALIDATION", "Hộp việc chỉ xem được của chính bạn.");
    }
    return ResponseEntity.ok(
        WorkflowWebMapper.inbox(inbox.list(businessType, period, limit, cursor)));
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<WorkflowTaskResult> completeWorkflowTask(
      String taskId, String idempotencyKey, String ifMatch, WorkflowTaskCompletion body) {
    long expected = ETags.expectedVersion(ifMatch, MODULE);
    CompleteTaskService.Result result =
        completer.complete(
            new CompleteTaskService.Command(
                taskId,
                expected,
                Decision.valueOf(body.getDecision().getValue()),
                body.getComment()));
    return ResponseEntity.ok(WorkflowWebMapper.result(result));
  }

  @Override
  @Idempotent(schema = "flowable")
  public ResponseEntity<ApproveTenantMove202Response> batchCompleteWorkflowTasks(
      String idempotencyKey, WorkflowBatchCompletion body) {
    List<BatchCompleteService.Item> items =
        body.getItems().stream()
            .map(i -> new BatchCompleteService.Item(i.getTaskId(), i.getRowVersion()))
            .toList();
    BatchJob job =
        batches.submit(
            new BatchCompleteService.Command(
                Decision.valueOf(body.getDecision().getValue()), body.getComment(), items));
    Job contract = WorkflowBatchJobSource.toJob(job, clock.instant());
    ResponseEntity<Job> accepted = Jobs.accepted(contract);
    return ResponseEntity.status(accepted.getStatusCode())
        .headers(accepted.getHeaders())
        .body(WorkflowWebMapper.job(contract));
  }

  @Override
  public ResponseEntity<WorkflowHistory> getWorkflowHistory(String businessKey) {
    return ResponseEntity.ok(WorkflowWebMapper.history(histories.get(businessKey)));
  }
}
