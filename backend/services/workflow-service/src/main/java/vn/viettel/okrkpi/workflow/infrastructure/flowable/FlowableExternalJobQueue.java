package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;

/**
 * {@link ExternalJobQueue} trên API external worker của Flowable 7 ({@code
 * ManagementService#createExternalWorkerJobAcquireBuilder}). Người quyết định và ý kiến lấy từ user
 * task kết thúc gần nhất của tiến trình (biến cục bộ {@code taskComment} do DV-06-02 ghi).
 */
@Component
public class FlowableExternalJobQueue implements ExternalJobQueue {

  private final ManagementService management;
  private final RuntimeService runtime;
  private final HistoryService history;

  public FlowableExternalJobQueue(
      ManagementService management, RuntimeService runtime, HistoryService history) {
    this.management = management;
    this.runtime = runtime;
    this.history = history;
  }

  @Override
  public List<CallbackJob> acquire(Set<String> topics, int max, Duration lock, String workerId) {
    List<CallbackJob> result = new ArrayList<>();
    for (String topic : topics) {
      int remaining = max - result.size();
      if (remaining <= 0) {
        break;
      }
      List<AcquiredExternalWorkerJob> jobs =
          management
              .createExternalWorkerJobAcquireBuilder()
              .topic(topic, lock)
              .acquireAndLock(remaining, workerId);
      for (AcquiredExternalWorkerJob job : jobs) {
        toCallback(topic, job)
            .ifPresentOrElse(
                result::add,
                () ->
                    management
                        .createExternalWorkerJobFailureBuilder(job.getId(), workerId)
                        .errorMessage("Tiến trình không có khoá nghiệp vụ hoặc tenant hợp lệ")
                        .retries(0)
                        .fail());
      }
    }
    return result;
  }

  private Optional<CallbackJob> toCallback(String topic, AcquiredExternalWorkerJob job) {
    ProcessInstance instance =
        runtime
            .createProcessInstanceQuery()
            .processInstanceId(job.getProcessInstanceId())
            .singleResult();
    Optional<UUID> tenant = uuid(job.getTenantId());
    if (instance == null || tenant.isEmpty()) {
      return Optional.empty();
    }
    Optional<BusinessKey> key = BusinessKey.parse(instance.getBusinessKey());
    if (key.isEmpty()) {
      return Optional.empty();
    }
    UUID decider = null;
    String comment = null;
    List<HistoricTaskInstance> last =
        history
            .createHistoricTaskInstanceQuery()
            .processInstanceId(job.getProcessInstanceId())
            .finished()
            .orderByHistoricTaskInstanceEndTime()
            .desc()
            .listPage(0, 1);
    if (!last.isEmpty()) {
      decider = uuid(last.get(0).getAssignee()).orElse(null);
      HistoricVariableInstance note =
          history
              .createHistoricVariableInstanceQuery()
              .taskId(last.get(0).getId())
              .variableName(FlowableTaskEngine.TASK_COMMENT_VARIABLE)
              .singleResult();
      comment = note == null || note.getValue() == null ? null : note.getValue().toString();
    }
    return Optional.of(
        new CallbackJob(
            job.getId(),
            topic,
            tenant.get(),
            job.getProcessInstanceId(),
            job.getElementId(),
            key.get(),
            job.getVariables(),
            decider,
            comment,
            job.getRetries()));
  }

  @Override
  public void complete(CallbackJob job, String workerId) {
    management.createExternalWorkerCompletionBuilder(job.jobId(), workerId).complete();
  }

  @Override
  public void fail(
      CallbackJob job, String workerId, String message, int retriesLeft, Duration retryAfter) {
    management
        .createExternalWorkerJobFailureBuilder(job.jobId(), workerId)
        .errorMessage(message)
        .retries(retriesLeft)
        .retryTimeout(retryAfter)
        .fail();
  }

  private static Optional<UUID> uuid(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
