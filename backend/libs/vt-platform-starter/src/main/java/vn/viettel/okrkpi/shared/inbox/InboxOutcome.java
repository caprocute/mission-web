package vn.viettel.okrkpi.shared.inbox;

/** NEN-012 · Kết quả xử lý một thông điệp tại inbox consumer, dùng làm nhãn metric Micrometer. */
public enum InboxOutcome {
  /** Xử lý nghiệp vụ và commit thành công. */
  PROCESSED("processed"),
  /** Bỏ qua do {@code (consumer, event_id)} đã có trong {@code inbox_processed}. */
  DUPLICATE_SKIPPED("duplicate_skipped"),
  /** Bỏ qua do {@code aggregateVersion <= last_version} trong {@code inbox_aggregate_version}. */
  STALE_SKIPPED("stale_skipped"),
  /** Chuyển sang topic thử lại {@code <topic>.retry.<consumer>} sau khi hết lượt thử tại chỗ. */
  RETRIED("retried"),
  /** Chuyển sang topic thư chết {@code <topic>.dlq.<consumer>}. */
  DLQ("dlq");

  private final String tagValue;

  InboxOutcome(String tagValue) {
    this.tagValue = tagValue;
  }

  public String tagValue() {
    return tagValue;
  }
}
