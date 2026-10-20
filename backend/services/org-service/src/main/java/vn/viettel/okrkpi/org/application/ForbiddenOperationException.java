package vn.viettel.okrkpi.org.application;

/** PolicyPort từ chối theo vai trò hoặc phạm vi dữ liệu: 403 {@code COMMON-403-OUT-OF-SCOPE}. */
public class ForbiddenOperationException extends RuntimeException {

  public ForbiddenOperationException(String detail) {
    super(detail);
  }
}
