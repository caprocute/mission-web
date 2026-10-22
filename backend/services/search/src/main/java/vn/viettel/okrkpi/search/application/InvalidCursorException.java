package vn.viettel.okrkpi.search.application;

/** Con trỏ trang không hợp lệ (400). */
public class InvalidCursorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidCursorException() {
    super("Con trỏ trang không hợp lệ");
  }
}
