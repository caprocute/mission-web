package vn.viettel.okrkpi.org.domain;

/** Lệch {@code row_version} so với {@code If-Match}: 409 {@code ORG-409-VERSION-CONFLICT}. */
public class VersionConflictException extends RuntimeException {

  private final Integer expected;
  private final Integer actual;

  public VersionConflictException(Integer expected, Integer actual) {
    super(expected == null
        ? "Dữ liệu đã được người khác cập nhật."
        : "Phiên bản bạn sửa là " + expected + ", phiên bản hiện tại là " + actual + ".");
    this.expected = expected;
    this.actual = actual;
  }

  public Integer expected() {
    return expected;
  }

  public Integer actual() {
    return actual;
  }
}
