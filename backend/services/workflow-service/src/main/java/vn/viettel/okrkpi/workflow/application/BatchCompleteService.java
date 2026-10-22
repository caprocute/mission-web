package vn.viettel.okrkpi.workflow.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.BatchDispatcher;
import vn.viettel.okrkpi.workflow.application.port.BatchJobStore;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BatchJob;
import vn.viettel.okrkpi.workflow.domain.CompletionRules;
import vn.viettel.okrkpi.workflow.domain.Decision;

/**
 * Duyệt hàng loạt ({@code batchCompleteWorkflowTasks}, SCR-0203 "Duyệt hàng loạt (đủ điều kiện)"):
 * nhận tối đa 200 mục, trả 202 kèm {@code Job}; mỗi mục hoàn thành trong giao dịch riêng nên mục
 * lỗi không làm hỏng mục khác, kết quả từng mục ghi vào {@code flowable.wf_task_batch}.
 */
@Service
public class BatchCompleteService {

  private static final Logger log = LoggerFactory.getLogger(BatchCompleteService.class);

  private final BatchJobStore jobs;
  private final CompleteTaskService completer;
  private final CurrentApprover currentApprover;
  private final ApprovalAccessPolicy policy;
  private final BatchDispatcher dispatcher;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public BatchCompleteService(
      BatchJobStore jobs,
      CompleteTaskService completer,
      CurrentApprover currentApprover,
      ApprovalAccessPolicy policy,
      BatchDispatcher dispatcher,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jobs = jobs;
    this.completer = completer;
    this.currentApprover = currentApprover;
    this.policy = policy;
    this.dispatcher = dispatcher;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setTimeout(5);
    this.clock = clock;
  }

  /**
   * Nhận lô: kiểm số mục và ý kiến, kiểm quyền duyệt lô, tạo tác vụ {@code CHO} và lên lịch xử lý
   * sau khi giao dịch cam kết.
   */
  @Transactional(timeout = 5)
  public BatchJob submit(Command command) {
    Approver approver = currentApprover.current();
    if (command.items().size() > CompletionRules.MAX_BATCH_ITEMS) {
      throw new BusinessException(
          "WORKFLOW-422-BATCH-TOO-LARGE", "Mỗi lần duyệt hàng loạt tối đa 200 việc.");
    }
    if (command.items().isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", "Chưa chọn việc nào để duyệt.");
    }
    CompleteTaskService.validateComment(command.decision(), command.comment());
    AuthzResource resource =
        AuthzResource.builder()
            .type("workflow_task")
            .tenantId(approver.tenantId().toString())
            .ownerUnitId(approver.unitId() == null ? null : approver.unitId().toString())
            .classification("C1")
            .build();
    if (command.decision() == Decision.TRA_LAI) {
      policy.checkReturn(resource);
    } else {
      policy.checkBatchApprove(resource);
    }
    BatchJob job =
        BatchJob.accepted(
            Ids.newUuidV7(),
            approver.tenantId(),
            approver.actorId(),
            command.decision(),
            command.items().size(),
            clock.instant());
    jobs.create(job);
    dispatcher.dispatchAfterCommit(() -> run(job, command));
    return job;
  }

  /** Trạng thái tác vụ của tenant, rỗng khi không có. */
  @Transactional(readOnly = true, timeout = 5)
  public Optional<BatchJob> find(UUID tenantId, UUID jobId) {
    return jobs.find(tenantId, jobId);
  }

  /** Xử lý lô ở luồng nền (đã mang ngữ cảnh người gửi). */
  void run(BatchJob accepted, Command command) {
    BatchJob job = withStatus(accepted, BatchJob.Status.DANG_CHAY, accepted.results());
    try {
      save(job);
      List<BatchJob.ItemResult> results = new ArrayList<>();
      for (Item item : command.items()) {
        results.add(process(item, command));
        job = withStatus(job, BatchJob.Status.DANG_CHAY, results);
        save(job);
      }
      save(withStatus(job, BatchJob.Status.HOAN_TAT, results));
    } catch (RuntimeException e) {
      log.error("Lô duyệt {} dừng giữa chừng", accepted.id(), e);
      save(withStatus(job, BatchJob.Status.THAT_BAI, job.results()));
    }
  }

  private BatchJob.ItemResult process(Item item, Command command) {
    try {
      completer.completeBatchItem(
          new CompleteTaskService.Command(
              item.taskId(), item.rowVersion(), command.decision(), command.comment()));
      return new BatchJob.ItemResult(item.taskId(), 200, null);
    } catch (BusinessException e) {
      return new BatchJob.ItemResult(item.taskId(), e.errorCode().status(), e.errorCode().code());
    } catch (VersionConflictException e) {
      return new BatchJob.ItemResult(
          item.taskId(), 409, CompleteTaskService.MODULE + "-409-VERSION-CONFLICT");
    } catch (WorkflowNotFoundException e) {
      return new BatchJob.ItemResult(item.taskId(), 404, null);
    } catch (RuntimeException e) {
      log.warn("Mục {} của lô duyệt lỗi ngoài dự kiến", item.taskId(), e);
      return new BatchJob.ItemResult(item.taskId(), 500, null);
    }
  }

  private BatchJob withStatus(
      BatchJob job, BatchJob.Status status, List<BatchJob.ItemResult> results) {
    int done = (int) results.stream().filter(BatchJob.ItemResult::succeeded).count();
    return new BatchJob(
        job.id(),
        job.tenantId(),
        job.requestedBy(),
        job.decision(),
        status,
        job.totalItems(),
        done,
        results.size() - done,
        results,
        clock.instant());
  }

  private void save(BatchJob job) {
    transactions.executeWithoutResult(tx -> jobs.update(job));
  }

  /**
   * Yêu cầu duyệt hàng loạt.
   *
   * @param decision quyết định áp cho mọi mục
   * @param comment ý kiến chung
   * @param items các mục kèm phiên bản
   */
  public record Command(Decision decision, String comment, List<Item> items) {

    public Command {
      Objects.requireNonNull(decision, "decision");
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  /**
   * Một mục của lô.
   *
   * @param taskId mã user task
   * @param rowVersion phiên bản đã đọc ở hộp việc
   */
  public record Item(String taskId, long rowVersion) {

    public Item {
      Objects.requireNonNull(taskId, "taskId");
    }
  }
}
