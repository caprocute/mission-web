package vn.viettel.okrkpi.config.application.port;

import java.io.Serial;

/** CSDL từ chối ghi kỳ vì chồng lấn ({@code ex_period__no_overlap}) hoặc trùng mã. */
public class PeriodConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Loại xung đột. */
  public enum Kind {
    OVERLAP,
    DUPLICATE_CODE
  }

  private final Kind kind;

  public PeriodConflictException(Kind kind, Throwable cause) {
    super("Xung đột khi ghi kỳ: " + kind, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
