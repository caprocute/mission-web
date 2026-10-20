package vn.viettel.okrkpi.shared.inbox;

/**
 * NEN-012 · Tên tiêu đề Kafka trên topic thử lại ({@code <topic>.retry.<consumer>}) và thư chết
 * ({@code <topic>.dlq.<consumer>}) theo TL-03 "Inbox idempotent". Các tiêu đề phong bì sự kiện gốc
 * dùng hằng ở {@link vn.viettel.okrkpi.shared.outbox.OutboxHeaders}.
 */
public final class InboxHeaders {

  /** Bậc thử lại hiện tại (1, 2, 3 ứng với độ trễ 1 phút, 5 phút, 30 phút của TL-03). */
  public static final String RETRY_TIER = "inbox.retry.tier";

  /** Độ trễ của bậc thử lại hiện tại tính bằng mili-giây. */
  public static final String RETRY_DELAY_MS = "inbox.retry.delay_ms";

  /** Thời điểm sớm nhất được phép xử lý lại (ISO-8601 UTC). */
  public static final String RETRY_NOT_BEFORE = "inbox.retry.not_before";

  /** Thông điệp lỗi tạm thời của lần thử gần nhất. */
  public static final String RETRY_LAST_ERROR = "inbox.retry.last_error";

  /** Lý do chuyển vào thư chết (DLQ). */
  public static final String DLQ_REASON = "dlq.reason";

  /** Mã lỗi ổn định khi chuyển vào thư chết. */
  public static final String DLQ_ERROR_CODE = "dlq.error_code";

  /** Tên lớp ngoại lệ gây ra chuyển thư chết. */
  public static final String DLQ_EXCEPTION_CLASS = "dlq.exception_class";

  /** Topic nguồn ban đầu của sự kiện lỗi. */
  public static final String DLQ_SOURCE_TOPIC = "dlq.source_topic";

  /** Định danh consumer gặp lỗi. */
  public static final String DLQ_CONSUMER = "dlq.consumer";

  /** Thời điểm chuyển vào thư chết (ISO-8601 UTC). */
  public static final String DLQ_FAILED_AT = "dlq.failed_at";

  private InboxHeaders() {}
}
