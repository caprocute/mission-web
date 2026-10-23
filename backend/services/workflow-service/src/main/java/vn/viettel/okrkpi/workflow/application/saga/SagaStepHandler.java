package vn.viettel.okrkpi.workflow.application.saga;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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

  /**
   * Thăm dò kết quả lệnh {@code commandEventId} khi bên nhận chưa phát sự kiện trả lời (tác vụ dài
   * {@code /jobs/{id}} của dịch vụ đích). Khung gọi trong lượt đối chiếu định kỳ của saga đang chờ;
   * rỗng là chưa có kết quả. Mặc định không thăm dò, chỉ chờ sự kiện.
   *
   * @param stepStartedAt lúc bắt đầu lần thử hiện tại, để bộ xử lý so với ngân sách thời gian
   */
  default Optional<StepReply> poll(
      StepInvocation invocation, UUID commandEventId, Instant stepStartedAt) {
    return Optional.empty();
  }

  /**
   * Kết quả thăm dò: xong, lỗi (tính là một lần thử thất bại của bước), hoặc giữ bước chờ người vận
   * hành quyết định ({@code hold}: không tính lần thử, saga sang {@code LOI_CAN_XU_LY}).
   */
  record StepReply(boolean success, String error, boolean hold) {

    public StepReply(boolean success, String error) {
      this(success, error, false);
    }

    public static StepReply done() {
      return new StepReply(true, null, false);
    }

    public static StepReply failed(String error) {
      return new StepReply(false, error, false);
    }

    public static StepReply hold(String reason) {
      return new StepReply(false, reason, true);
    }
  }
}
