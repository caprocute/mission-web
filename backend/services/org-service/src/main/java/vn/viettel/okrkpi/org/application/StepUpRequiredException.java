package vn.viettel.okrkpi.org.application;

/** Thao tác cần AAL2 trong cửa sổ {@code auth_time}: 401 kèm {@code WWW-Authenticate} theo RFC 9470. */
public class StepUpRequiredException extends RuntimeException {

  private final long maxAgeSeconds;

  public StepUpRequiredException(long maxAgeSeconds) {
    super("Thao tác cần xác thực bổ sung.");
    this.maxAgeSeconds = maxAgeSeconds;
  }

  public long maxAgeSeconds() {
    return maxAgeSeconds;
  }
}
