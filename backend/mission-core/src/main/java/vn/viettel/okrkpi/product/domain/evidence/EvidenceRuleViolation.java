package vn.viettel.okrkpi.product.domain.evidence;

import java.io.Serial;
import java.util.Objects;

/**
 * Vi phạm quy tắc của máy trạng thái minh chứng; {@link #code()} là mã lỗi của hợp đồng {@code
 * products.yaml} để tầng ứng dụng ánh xạ sang RFC 9457.
 */
public final class EvidenceRuleViolation extends IllegalStateException {

  @Serial private static final long serialVersionUID = 1L;

  /** Phiên bản không ở trạng thái nguồn của lệnh. */
  public static final String INVALID_STATE = "PRODUCT-409-INVALID-STATE";

  /** Phiên bản đã nộp, không sửa được nội dung (BR-07-01). */
  public static final String VERSION_FROZEN = "PRODUCT-409-VERSION-FROZEN";

  /** Chưa gắn tệp hoặc chưa có mã băm. */
  public static final String NO_OBJECT = "PRODUCT-422-NO-OBJECT";

  /** Tệp chưa quét sạch (BR-07-04). */
  public static final String NOT_CLEAN = "PRODUCT-422-NOT-CLEAN";

  /** Ô do AI điền chưa được người nộp xác nhận (BR-07-09). */
  public static final String AI_NOT_CONFIRMED = "PRODUCT-422-AI-NOT-CONFIRMED";

  /** Yêu cầu bổ sung hoặc từ chối thiếu nhận xét (BR-07-08, MSG-07-10). */
  public static final String COMMENT_REQUIRED = "PRODUCT-422-COMMENT-REQUIRED";

  private final String code;

  public EvidenceRuleViolation(String code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
  }

  public String code() {
    return code;
  }
}
