package vn.viettel.okrkpi.org.application.scim;

/**
 * Lỗi trả cho client SCIM theo RFC 7644 mục 3.12: mã HTTP, {@code scimType} (tuỳ chọn) và mô tả. Mô tả không bao giờ
 * chứa số định danh hay ngày sinh.
 */
public class ScimException extends RuntimeException {

  private final int status;
  private final String scimType;

  public ScimException(int status, String scimType, String detail) {
    super(detail);
    this.status = status;
    this.scimType = scimType;
  }

  public int status() {
    return status;
  }

  public String scimType() {
    return scimType;
  }

  public static ScimException invalidValue(String detail) {
    return new ScimException(400, "invalidValue", detail);
  }

  public static ScimException notFound() {
    return new ScimException(404, null, "Không tìm thấy tài nguyên.");
  }

  public static ScimException uniqueness(String detail) {
    return new ScimException(409, "uniqueness", detail);
  }
}
