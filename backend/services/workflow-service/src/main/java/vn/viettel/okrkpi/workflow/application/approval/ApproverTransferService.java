package vn.viettel.okrkpi.workflow.application.approval;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.TaskReassignment;
import vn.viettel.okrkpi.workflow.domain.CompletionRules;

/**
 * Chuyển người duyệt khi điều động nhân sự (TL-01 G2: "việc chuyển người duyệt khi có điều động là
 * một thao tác uỷ quyền có lưu vết"; TL-02a QT-02 "người duyệt vắng mặt"). Cấp duyệt đã phân giải
 * lúc khởi tạo không đổi; chỉ user task đang mở được giao cho người thay, kèm lý do bắt buộc.
 *
 * <p>Đây là cổng ứng dụng; API {@code reassignWorkflowTask} (BS-01 SCR-1506) thuộc DV-06-10.
 */
@Service
public class ApproverTransferService {

  private final TaskReassignment engine;

  public ApproverTransferService(TaskReassignment engine) {
    this.engine = engine;
  }

  /**
   * Chuyển user task cho người duyệt mới; trả {@code false} khi người mới đã là người được giao.
   */
  @Transactional(timeout = 5)
  public boolean transfer(Command command) {
    String reason = command.reason() == null ? "" : command.reason().strip();
    if (reason.isEmpty() || reason.length() > CompletionRules.MAX_COMMENT) {
      throw new ApprovalSetupException("Lý do chuyển người duyệt phải có và tối đa 2.000 ký tự");
    }
    TaskReassignment.ReassignableTask task =
        engine
            .find(command.tenantId(), command.taskId())
            .orElseThrow(WorkflowNotFoundException::new);
    if (!task.delegationAllowed()) {
      throw new ApprovalSetupException("Mẫu luồng của đơn vị không cho phép chuyển người duyệt");
    }
    if (command.newApproverId().equals(task.currentAssigneeId())) {
      return false;
    }
    engine.reassign(task, command.newApproverId(), command.actorId(), reason);
    return true;
  }

  /**
   * Yêu cầu chuyển người duyệt.
   *
   * @param tenantId tenant
   * @param taskId user task đang mở
   * @param newApproverId người duyệt thay
   * @param actorId người thực hiện thao tác (quản trị tenant hoặc người được uỷ quyền phân công)
   * @param reason lý do, bắt buộc
   */
  public record Command(
      UUID tenantId, String taskId, UUID newApproverId, UUID actorId, String reason) {

    public Command {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(newApproverId, "newApproverId");
      Objects.requireNonNull(actorId, "actorId");
    }
  }
}
