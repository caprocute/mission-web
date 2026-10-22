package vn.viettel.okrkpi.docgen.application.render;

/** Gói hồ sơ hoặc tác vụ không có, hoặc nằm ngoài phạm vi người gọi: trả 404 như nhau. */
public class DocgenNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public DocgenNotFoundException(String message) {
    super(message);
  }
}
