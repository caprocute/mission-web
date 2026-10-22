package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lõi máy trạng thái nhiệm vụ (TL-01): kiểm theo thứ tự kỳ khoá, dòng chuyển có trong bảng, vai
 * trò, lý do bắt buộc, rồi điều kiện CEL. Ngữ cảnh CEL chỉ được dựng khi dòng chuyển có guard.
 */
public final class TaskStateMachine {

  private final TransitionTable table;
  private final GuardEvaluator guards;

  public TaskStateMachine(TransitionTable table, GuardEvaluator guards) {
    this.table = Objects.requireNonNull(table, "table");
    this.guards = Objects.requireNonNull(guards, "guards");
  }

  public TransitionTable table() {
    return table;
  }

  public TransitionResult apply(
      TaskStatus current,
      TaskCommand command,
      TaskActor actor,
      boolean periodWritable,
      Supplier<Map<String, Object>> activation) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(actor, "actor");
    if (!periodWritable) {
      return new TransitionResult.Rejected(
          RejectionCode.PERIOD_LOCKED, "Kỳ đã khoá, không thể chuyển trạng thái");
    }
    Transition t = table.find(current, command.code()).orElse(null);
    if (t == null) {
      return new TransitionResult.Rejected(
          RejectionCode.TRANSITION_NOT_ALLOWED,
          "Không cho phép " + command.code() + " từ trạng thái " + current);
    }
    if (actor.roles().stream().noneMatch(t.allowedRoles()::contains)) {
      return new TransitionResult.Rejected(
          RejectionCode.ROLE_NOT_ALLOWED, "Vai trò không được thực hiện thao tác này");
    }
    String reason = command.reason();
    if (t.reasonRequired() && reason.isBlank()) {
      return new TransitionResult.Rejected(
          RejectionCode.REASON_REQUIRED, "Thao tác này bắt buộc nêu lý do");
    }
    if (t.guardCel() != null && !guards.test(t.guardCel(), activation.get())) {
      return new TransitionResult.Rejected(
          RejectionCode.GUARD_FAILED, "Chưa đáp ứng điều kiện: " + t.guardCel());
    }
    return new TransitionResult.Accepted(
        current, t.to(), command.code(), table.policyVersion(), reason);
  }
}
