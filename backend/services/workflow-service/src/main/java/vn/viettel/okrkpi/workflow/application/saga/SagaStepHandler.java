package vn.viettel.okrkpi.workflow.application.saga;

import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Bộ xử lý một bước saga do task của từng saga cung cấp (DV-06-06, DV-03-04, CORE-05-10...).
 *
 * <p>{@link #execute} chạy trong cùng giao dịch với việc ghi {@code saga_step} và hoàn thành lượt
 * của engine, nên chỉ được làm việc ngắn: ghi lệnh vào outbox, gọi API idempotent. Việc nặng chạy ở
 * dịch vụ đích rồi trả lời bằng sự kiện ({@link StepResult.AwaitingReply}). Ném ngoại lệ là một lần
 * thử thất bại; khung tự thử lại theo chính sách của bước.
 */
public interface SagaStepHandler {

  SagaType sagaType();

  String stepCode();

  StepResult execute(StepInvocation invocation);

  /**
   * Hoàn tác bước đã xong. Chỉ được gọi khi bước khai {@code compensationCode}; mặc định không làm
   * gì.
   */
  default void compensate(StepInvocation invocation) {
    // Bước không khai bù trừ thì khung không gọi tới đây.
  }
}
