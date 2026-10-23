package vn.viettel.okrkpi.integ.application.outbound;

/**
 * Sự kiện không được gửi ra hệ ngoài vì vi phạm chính sách chia sẻ của bộ kết nối (DV-13-02). Thông
 * điệp chỉ mang lý do và mã kết nối, không bao giờ mang dữ liệu của sự kiện.
 */
public class OutboundRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Lý do từ chối. */
  public enum Reason {
    /** Mức phân loại của sự kiện hoặc dữ liệu vượt mức bộ kết nối được chia sẻ. */
    CLASSIFICATION_NOT_ALLOWED,
    /** Dữ liệu còn chứa giá trị đã niêm phong (bản mã C2, C3 nội bộ). */
    SEALED_VALUE,
    /** Không có mã ngoài cho một mã nội bộ cần gửi. */
    UNMAPPED_REFERENCE,
    /** Thuộc tính phong bì không hợp lệ. */
    INVALID_ENVELOPE
  }

  private final Reason reason;

  public OutboundRejectedException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
