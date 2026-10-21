package vn.viettel.okrkpi.org.domain;

/** Không tìm thấy hoặc ngoài phạm vi người gọi (không lộ sự tồn tại). */
public class OrgNotFoundException extends RuntimeException {

  public OrgNotFoundException(String detail) {
    super(detail);
  }
}
