package vn.viettel.okrkpi.search.application;

/** Cụm OpenSearch tạm thời không ghi được (mất kết nối, 429, 5xx); lô được thử lại nguyên vẹn. */
public class TransientIndexException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TransientIndexException(String message, Throwable cause) {
    super(message, cause);
  }

  public TransientIndexException(String message) {
    super(message);
  }
}
