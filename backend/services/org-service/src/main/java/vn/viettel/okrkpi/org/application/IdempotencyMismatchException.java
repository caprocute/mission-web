package vn.viettel.okrkpi.org.application;

/** Cùng {@code Idempotency-Key} nhưng khác nội dung: 422 {@code COMMON-422-IDEMPOTENCY-MISMATCH}. */
public class IdempotencyMismatchException extends RuntimeException {

  public IdempotencyMismatchException() {
    super("Khoá chống ghi trùng đã được dùng cho một yêu cầu khác.");
  }
}
