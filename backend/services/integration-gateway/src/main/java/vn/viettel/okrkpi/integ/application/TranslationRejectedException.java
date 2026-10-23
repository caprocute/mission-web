package vn.viettel.okrkpi.integ.application;

import java.util.Objects;

/**
 * Translator từ chối thông điệp vì lý do nghiệp vụ không tự khắc phục được bằng thử lại (ví dụ đơn
 * vị nhận chưa ánh xạ): luồng vào không thử lại mà ghi thẳng thư chết với mã lý do để quản trị
 * tenant xử lý rồi phát lại (DV-13-03). Thông điệp lỗi chỉ chứa mã phía ngoài, không chứa nội dung.
 */
public class TranslationRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String reasonCode;

  public TranslationRejectedException(String reasonCode, String message) {
    super(message);
    this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
  }

  /** Mã lý do ghi vào {@code dead_letter.error_code}. */
  public String reasonCode() {
    return reasonCode;
  }
}
