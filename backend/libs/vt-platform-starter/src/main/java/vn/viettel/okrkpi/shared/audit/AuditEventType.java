package vn.viettel.okrkpi.shared.audit;

/**
 * Loại sự kiện kiểm toán theo TL-01 "Tách nhật ký kiểm toán" và TL-03 (topic {@code sys.audit.v1}).
 * Tên hằng là suy luận; lược đồ Avro của topic do NEN-007 chốt và có thể đổi tên tương ứng.
 */
public enum AuditEventType {
  /** Đăng nhập của tài khoản quản trị. */
  ADMIN_LOGIN,
  /** Đăng xuất của tài khoản quản trị. */
  ADMIN_LOGOUT,
  /** Cấp, thu hồi, đổi vai trò hoặc phạm vi quyền. */
  PERMISSION_CHANGE,
  /** Xem hoặc giải mã dữ liệu C2, C3 (của người khác). */
  SENSITIVE_DATA_ACCESS,
  /** Xuất dữ liệu (đơn lẻ hoặc hàng loạt). */
  DATA_EXPORT,
  /** Thao tác quản trị khác. */
  ADMIN_ACTION,
  /** Thao tác ở mốc pháp lý (khoá kỳ, ký, quyết định). */
  LEGAL_MILESTONE
}
