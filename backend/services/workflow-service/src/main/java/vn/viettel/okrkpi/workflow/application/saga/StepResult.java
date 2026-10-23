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

  /**
   * Bước từ chối theo nghiệp vụ (ví dụ tiền điều kiện chốt kỳ không đạt): không thử lại; trước điểm
   * không quay lại thì saga bù trừ các bước đã xong, sau đó thì chờ người vận hành. {@code code} là
   * mã lỗi trong danh mục của hợp đồng, được trả trong Problem của tác vụ (DV-06-06).
   */
  record Rejected(String code, String detail) implements StepResult {
    public Rejected {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(detail, "detail");
    }

    /** Lỗi ghi vào {@code saga_step.last_error}: mã, dấu hai chấm, mô tả tiếng Việt. */
    public String error() {
      return code + ": " + detail;
    }
  }

  static StepResult completed() {
    return new Completed();
  }

  static StepResult awaiting(UUID commandEventId) {
    return new AwaitingReply(commandEventId);
  }

  static StepResult rejected(String code, String detail) {
    return new Rejected(code, detail);
  }
}
