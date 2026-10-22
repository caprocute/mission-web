package vn.viettel.okrkpi.config.domain;

/** Chuyển trạng thái phiên bản UI Pack không có trong máy trạng thái QT-18. */
public class IllegalUiPackTransitionException extends RuntimeException {

  private final UiPackVersionStatus from;
  private final UiPackVersionStatus to;

  public IllegalUiPackTransitionException(UiPackVersionStatus from, UiPackVersionStatus to) {
    super("Không chuyển được phiên bản UI Pack từ " + from + " sang " + to);
    this.from = from;
    this.to = to;
  }

  public UiPackVersionStatus from() {
    return from;
  }

  public UiPackVersionStatus to() {
    return to;
  }
}
