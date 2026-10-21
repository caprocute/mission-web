package vn.viettel.okrkpi.shared.idempotency;

import java.io.Serial;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** Lỗi của quy ước {@code Idempotency-Key}; {@link IdempotencyProblemHandler} trả Problem. */
public final class IdempotencyExceptions {

  /** Mã hợp đồng của cùng khoá khác nội dung ({@code common.yaml} tham số IdempotencyKey). */
  public static final String MISMATCH_CODE = "COMMON-422-IDEMPOTENCY-MISMATCH";

  private IdempotencyExceptions() {}

  /** Thiếu hoặc sai dạng header ở thao tác bắt buộc: 400. */
  public static final class KeyRequired extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final boolean present;

    public KeyRequired(boolean present) {
      super("IDEMPOTENCY-KEY-REQUIRED", null, false, false);
      this.present = present;
    }

    /** Header có mặt nhưng sai dạng (rỗng, quá 64 ký tự, ký tự lạ). */
    public boolean present() {
      return present;
    }
  }

  /** Cùng khoá đang được yêu cầu khác xử lý: 409 kèm {@code Retry-After}. */
  public static final class InProgress extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public InProgress(long retryAfterSeconds) {
      super("IDEMPOTENCY-IN-PROGRESS", null, false, false);
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  /** Cùng khoá, khác nội dung: 422 {@value #MISMATCH_CODE}. */
  public static final class Mismatch extends BusinessException {
    @Serial private static final long serialVersionUID = 1L;

    public Mismatch() {
      super(
          MISMATCH_CODE,
          "Mã chống gửi trùng đã được dùng cho một yêu cầu có nội dung khác. Vui lòng tạo yêu cầu"
              + " mới.");
    }
  }
}
