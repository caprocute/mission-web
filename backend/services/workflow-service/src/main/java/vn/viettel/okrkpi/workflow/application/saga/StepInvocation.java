package vn.viettel.okrkpi.workflow.application.saga;

import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Thông tin worker chuyển cho bộ xử lý bước.
 *
 * @param attempt số thứ tự lần thử hiện tại, đếm từ 1; bộ xử lý dùng cùng {@code sagaId} và {@code
 *     stepNo} làm khoá idempotency của lệnh phát đi, để lần thử lại hay lần chạy lại sau khi pod
 *     chết không tạo tác dụng phụ trùng
 * @param notes ghi chú bền của saga do lệnh điều khiển của từng loại saga đặt (ví dụ chạy lại hồ sơ
 *     lỗi, chấp nhận để lại lỗi)
 */
public record StepInvocation(
    UUID sagaId,
    UUID tenantId,
    SagaType sagaType,
    UUID correlationId,
    int stepNo,
    String stepCode,
    String compensationCode,
    int attempt,
    Map<String, Object> parameters,
    Map<String, Object> notes) {

  public StepInvocation {
    notes = notes == null ? Map.of() : Map.copyOf(notes);
  }

  /** Lần gọi không kèm ghi chú của saga. */
  public StepInvocation(
      UUID sagaId,
      UUID tenantId,
      SagaType sagaType,
      UUID correlationId,
      int stepNo,
      String stepCode,
      String compensationCode,
      int attempt,
      Map<String, Object> parameters) {
    this(
        sagaId,
        tenantId,
        sagaType,
        correlationId,
        stepNo,
        stepCode,
        compensationCode,
        attempt,
        parameters,
        Map.of());
  }

  /** Khoá idempotency ổn định của bước, dùng cho {@code Idempotency-Key} hoặc {@code event_id}. */
  public String idempotencyKey() {
    return sagaId + ":" + stepNo + (compensationCode == null ? "" : ":bu-tru");
  }
}
