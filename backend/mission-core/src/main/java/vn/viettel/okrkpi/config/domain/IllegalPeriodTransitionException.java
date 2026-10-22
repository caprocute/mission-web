package vn.viettel.okrkpi.config.domain;

import java.io.Serial;

/** Chuyển trạng thái kỳ không thuộc năm chuyển hợp lệ của baseline mục 9.1. */
public class IllegalPeriodTransitionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final PeriodState from;
  private final PeriodState to;

  public IllegalPeriodTransitionException(PeriodState from, PeriodState to) {
    super("Chuyển trạng thái kỳ không hợp lệ: " + from + " -> " + to);
    this.from = from;
    this.to = to;
  }

  public PeriodState from() {
    return from;
  }

  public PeriodState to() {
    return to;
  }
}
