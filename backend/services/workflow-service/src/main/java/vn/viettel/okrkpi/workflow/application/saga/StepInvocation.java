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
    Map<String, Object> parameters) {

  /** Khoá idempotency ổn định của bước, dùng cho {@code Idempotency-Key} hoặc {@code event_id}. */
  public String idempotencyKey() {
    return sagaId + ":" + stepNo + (compensationCode == null ? "" : ":bu-tru");
  }
}
