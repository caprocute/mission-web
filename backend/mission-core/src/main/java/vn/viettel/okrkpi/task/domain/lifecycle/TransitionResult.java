package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Objects;

/**
 * Kết quả của {@link TaskStateMachine#apply}: chấp nhận kèm trạng thái mới, hoặc từ chối kèm mã.
 */
public sealed interface TransitionResult {

  record Accepted(
      TaskStatus from, TaskStatus to, String command, String policyVersion, String reason)
      implements TransitionResult {

    public Accepted {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(policyVersion, "policyVersion");
      reason = reason == null || reason.isBlank() ? null : reason;
    }
  }

  record Rejected(RejectionCode code, String message) implements TransitionResult {

    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
    }
  }
}
