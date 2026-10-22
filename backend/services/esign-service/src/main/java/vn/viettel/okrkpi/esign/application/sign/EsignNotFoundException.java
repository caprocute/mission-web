package vn.viettel.okrkpi.esign.application.sign;

/** Không có yêu cầu ký hoặc bản ghi nguồn trong tenant, hoặc người gọi không được xem (404). */
public class EsignNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EsignNotFoundException(String message) {
    super(message);
  }
}
