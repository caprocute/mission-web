package vn.viettel.okrkpi.workflow.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.application.port.TaskEngine;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CompletionRules;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.OpenTask;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Hoàn thành một user task ({@code completeWorkflowTask}, SCR-0203; BR-02-08, BR-02-09): kiểm ý
 * kiến, phiên bản {@code If-Match} so với {@code REV_}, người gọi là người được giao hoặc ứng viên
 * hợp lệ, tách vai trò qua OPA, rồi nhận việc và hoàn thành trong cùng giao dịch với bảng chiếu.
 */
@Service
public class CompleteTaskService {

  /** Phần {@code MODULE} của mã {@code WORKFLOW-409-VERSION-CONFLICT}. */
  static final String MODULE = "WORKFLOW";

  private final TaskEngine engine;
  private final CurrentApprover currentApprover;
  private final ApprovalAccessPolicy policy;

  public CompleteTaskService(
      TaskEngine engine, CurrentApprover currentApprover, ApprovalAccessPolicy policy) {
    this.engine = engine;
    this.currentApprover = currentApprover;
    this.policy = policy;
  }

  /** Hoàn thành một việc trong giao dịch của yêu cầu (đã gồm khoá Idempotency-Key). */
  @Transactional(timeout = 5)
  public Result complete(Command command) {
    return execute(command, false);
  }

  /** Hoàn thành một mục của lô trong giao dịch riêng: lỗi mục này không làm hỏng mục khác. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
  public Result completeBatchItem(Command command) {
    return execute(command, true);
  }

  private Result execute(Command command, boolean batch) {
    Approver approver = currentApprover.current();
    validateComment(command.decision(), command.comment());
    OpenTask task =
        engine
            .findOpen(approver.tenantId(), command.taskId())
            .orElseThrow(() -> missing(approver, command.taskId()));
    if (StageTemplate.byBusinessType(task.businessKey().type()).isPresent()) {
      // Mẫu phần 2: thao tác nghiệp vụ có step-up và ký số ở API của lõi (DV-06-04).
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Việc này được xử lý tại màn hình nghiệp vụ của hồ sơ.");
    }
    if (task.revision() != command.expectedVersion()) {
      throw VersionConflictException.of(MODULE, command.expectedVersion(), task.revision());
    }
    if (!task.actionableBy(approver)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Bạn không phải người được giao xử lý việc này.");
    }
    AuthzResource resource = resourceOf(task, approver);
    if (command.decision() == Decision.TRA_LAI) {
      policy.checkReturn(resource);
    } else if (batch) {
      policy.checkBatchApprove(resource);
    } else {
      policy.checkApprove(resource);
    }
    TaskEngine.Completion done =
        engine.complete(task, approver, command.decision(), blankToNull(command.comment()));
    return new Result(
        task.taskId(),
        task.businessKey(),
        command.decision(),
        done.completedAt(),
        done.processEnded());
  }

  private RuntimeException missing(Approver approver, String taskId) {
    if (engine.wasCompleted(approver.tenantId(), taskId)) {
      return new BusinessException("WORKFLOW-409-TASK-COMPLETED", "Việc này đã được xử lý.");
    }
    return new WorkflowNotFoundException();
  }

  static void validateComment(Decision decision, String comment) {
    Optional<CompletionRules.Violation> violation = CompletionRules.checkComment(decision, comment);
    if (violation.isEmpty()) {
      return;
    }
    if (violation.get() == CompletionRules.Violation.COMMENT_REQUIRED) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Vui lòng nhập lý do trả lại.",
          List.of(ProblemFieldError.of("comment", "Vui lòng nhập lý do trả lại.", "required")),
          "MSG-02-10");
    }
    throw new BusinessException(
        "COMMON-422-VALIDATION",
        "Ý kiến quá dài.",
        List.of(ProblemFieldError.of("comment", "Ý kiến tối đa 2.000 ký tự.", "maxLength")));
  }

  private static AuthzResource resourceOf(OpenTask task, Approver approver) {
    AuthzResource.Builder b =
        AuthzResource.builder()
            .type(task.businessKey().type().toLowerCase(java.util.Locale.ROOT))
            .id(task.businessKey().id().toString())
            .tenantId(task.tenantId().toString())
            .classification("C1");
    String unit =
        task.candidateGroups().isEmpty()
            ? (approver.unitId() == null ? null : approver.unitId().toString())
            : task.candidateGroups().get(0).unitId().toString();
    b.ownerUnitId(unit);
    if (task.assigneeId() != null) {
      b.assigneeIds(List.of(task.assigneeId().toString()));
    }
    if (task.initiatorId() != null) {
      b.ownerIds(List.of(task.initiatorId().toString()));
      b.participants(Map.of("initiator", List.of(task.initiatorId().toString())));
    }
    return b.build();
  }

  private static String blankToNull(String comment) {
    return comment == null || comment.isBlank() ? null : comment.trim();
  }

  /**
   * Yêu cầu hoàn thành.
   *
   * @param taskId mã user task
   * @param expectedVersion phiên bản trong {@code If-Match}
   * @param decision quyết định
   * @param comment ý kiến, bắt buộc khi trả lại
   */
  public record Command(String taskId, long expectedVersion, Decision decision, String comment) {

    public Command {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(decision, "decision");
    }
  }

  /**
   * Kết quả hoàn thành ({@code WorkflowTaskResult}).
   *
   * @param taskId mã user task
   * @param businessKey đối tượng nghiệp vụ
   * @param decision quyết định
   * @param completedAt thời điểm hoàn thành
   * @param processEnded tiến trình đã kết thúc
   */
  public record Result(
      String taskId,
      BusinessKey businessKey,
      Decision decision,
      Instant completedAt,
      boolean processEnded) {}
}
