package vn.viettel.okrkpi.workflow.domain.saga;

import java.time.Duration;

/** Quyết định sau một lần thử thất bại của bước hoặc của bù trừ. */
public sealed interface FailureDecision {

  /** Thử lại sau {@code backoff}. */
  record Retry(Duration backoff) implements FailureDecision {}

  /** Hết số lần thử trước điểm không quay lại: bắt đầu bù trừ ngược các bước đã xong. */
  record StartCompensation() implements FailureDecision {}

  /** Hết số lần thử: chuyển {@code LOI_CAN_XU_LY}, chờ người vận hành. */
  record NeedsOperator() implements FailureDecision {}
}
