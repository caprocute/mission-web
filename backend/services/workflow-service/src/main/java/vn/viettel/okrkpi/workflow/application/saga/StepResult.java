package vn.viettel.okrkpi.workflow.application.saga;

import java.util.Objects;
import java.util.UUID;

/** Kết quả một lần thực hiện bước. */
public sealed interface StepResult {

  /** Bước xong ngay trong lần thực hiện. */
  record Completed() implements StepResult {}

  /** Bước đã phát lệnh {@code commandEventId} và chờ sự kiện trả lời. */
  record AwaitingReply(UUID commandEventId) implements StepResult {
    public AwaitingReply {
      Objects.requireNonNull(commandEventId, "commandEventId");
    }
  }

  static StepResult completed() {
    return new Completed();
  }

  static StepResult awaiting(UUID commandEventId) {
    return new AwaitingReply(commandEventId);
  }
}
