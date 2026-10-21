package vn.viettel.okrkpi.shared.audit;

/**
 * Ghi sự kiện kiểm toán (TL-01 "Tách nhật ký kiểm toán"): đăng nhập quản trị, đổi quyền, truy cập
 * C2/C3, xuất dữ liệu, thao tác quản trị, thao tác ở mốc pháp lý. Kênh này tách khỏi nhật ký vận
 * hành: sự kiện đi qua {@link AuditSink} (outbox lên {@code sys.audit.v1} khi có NEN-011), không đi
 * qua logger của ứng dụng.
 */
public interface AuditLogger {

  void record(AuditEvent event);
}
