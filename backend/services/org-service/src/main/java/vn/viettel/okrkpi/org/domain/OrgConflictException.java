package vn.viettel.okrkpi.org.domain;

/** Xung đột trạng thái nghiệp vụ (409), ví dụ số quyết định đã tồn tại. */
public class OrgConflictException extends RuntimeException {

  private final String code;

  public OrgConflictException(String code, String detail) {
    super(detail);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
