package vn.viettel.okrkpi.shared.inbox;

import java.util.Objects;

/**
 * NEN-012 · Lỗi vĩnh viễn khi tiêu thụ sự kiện (TL-03 "Inbox idempotent"): thông điệp thiếu tiêu đề
 * bắt buộc ({@code id}, {@code tenantid}), không giải mã được theo lược đồ Avro, hoặc vi phạm bất
 * biến nghiệp vụ. Không thử lại tại chỗ; thông điệp được chuyển ngay sang {@code
 * <topic>.dlq.<consumer>} kèm tiêu đề nguyên nhân và ghi nhận metric {@code dlq}.
 */
public class PermanentInboxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Mã lỗi mặc định khi phương thức nghiệp vụ ném ngoại lệ không kèm mã riêng. */
  public static final String DEFAULT_ERROR_CODE = "INBOX_PERMANENT_ERROR";

  /** Mã lỗi khi thiếu hoặc sai định dạng tiêu đề bắt buộc. */
  public static final String INVALID_HEADER_CODE = "INBOX_INVALID_HEADER";

  /** Mã lỗi khi không giải tuần tự được thân Avro. */
  public static final String DESERIALIZATION_ERROR_CODE = "INBOX_DESERIALIZATION_ERROR";

  private final String errorCode;

  public PermanentInboxException(String message) {
    this(DEFAULT_ERROR_CODE, message, null);
  }

  public PermanentInboxException(String errorCode, String message) {
    this(errorCode, message, null);
  }

  public PermanentInboxException(String errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode =
        errorCode == null || errorCode.isBlank()
            ? DEFAULT_ERROR_CODE
            : Objects.requireNonNull(errorCode);
  }

  public String errorCode() {
    return errorCode;
  }
}
