package vn.viettel.okrkpi.shared.outbox;

/**
 * Không tuần tự hoá được thân sự kiện: registry lược đồ không phản hồi, lược đồ chưa đăng ký cho
 * topic, hoặc thân không khớp lược đồ. Ném ra trong giao dịch nghiệp vụ để giao dịch thất bại rõ
 * ràng thay vì ghi một dòng outbox không có lược đồ (NEN-011, suy luận theo TL-03).
 */
public class OutboxSerializationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OutboxSerializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
