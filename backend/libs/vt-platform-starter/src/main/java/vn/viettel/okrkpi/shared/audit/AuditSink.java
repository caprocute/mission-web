package vn.viettel.okrkpi.shared.audit;

/**
 * Nơi nhận sự kiện kiểm toán đã hoàn chỉnh. NEN-011 khai báo bean ghi qua {@code OutboxWriter} lên
 * topic {@link #TOPIC} trong cùng giao dịch; trước đó starter dùng {@link LoggingAuditSink} (kênh
 * nhật ký kiểm toán riêng).
 */
@FunctionalInterface
public interface AuditSink {

  /** Topic kỹ thuật của sự kiện kiểm toán (TL-01, TL-03). */
  String TOPIC = "sys.audit.v1";

  void publish(AuditEvent event);
}
