package vn.viettel.okrkpi.shared.authz;

/**
 * Ngoại lệ ném ra khi thao tác yêu cầu xác thực bước lên AAL2 ({@code x-mission.aal: 2} hoặc {@code
 * deny: ["step_up_required"]}) nhưng phiên hiện tại chưa có {@code acr=aal2} hoặc {@code auth_time}
 * đã quá cửa sổ cho phép (NEN-014, RFC 9470, {@code common.yaml}).
 */
public final class StepUpRequiredException extends RuntimeException {

  public static final String REQUIRED_ACR = "aal2";

  private final String operationId;
  private final long maxAgeSeconds;

  public StepUpRequiredException(String operationId, long maxAgeSeconds, String message) {
    super(message);
    this.operationId = operationId;
    this.maxAgeSeconds = maxAgeSeconds;
  }

  public String getOperationId() {
    return operationId;
  }

  public long getMaxAgeSeconds() {
    return maxAgeSeconds;
  }

  /**
   * Sinh giá trị header {@code WWW-Authenticate} đúng chuẩn RFC 9470 và {@code common.yaml}: {@code
   * Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=<giây>}.
   */
  public String wwwAuthenticateHeader() {
    return "Bearer error=\"insufficient_user_authentication\", acr_values=\""
        + REQUIRED_ACR
        + "\", max_age="
        + maxAgeSeconds;
  }
}
