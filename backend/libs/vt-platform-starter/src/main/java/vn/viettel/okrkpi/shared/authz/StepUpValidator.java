package vn.viettel.okrkpi.shared.authz;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Kiểm tra xác thực bước lên AAL2 ({@code acr=aal2} và {@code auth_time} trong cửa sổ cho phép)
 * theo NEN-014, TL-01 và RFC 9470.
 *
 * <p>Mặc định cửa sổ là 5 phút (300 giây) theo {@code common.yaml} và TL-01; cho phép cấu hình theo
 * tenant (ví dụ 15 phút theo BR-00-02 / {@code mfa_stepup_window_minutes}).
 */
public final class StepUpValidator {

  private static final long CLOCK_SKEW_TOLERANCE_SECONDS = 30L;

  private final OpaProperties properties;
  private final Clock clock;

  public StepUpValidator(OpaProperties properties) {
    this(properties, Clock.systemUTC());
  }

  public StepUpValidator(OpaProperties properties, Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public void verifyStepUp(OpaInput.Subject subject, String operationId) {
    Objects.requireNonNull(subject, "subject must not be null");
    UUID tenantId = parseUuidOrNull(subject.tenantId());
    Duration window = properties.stepUpWindowForTenant(tenantId);
    long maxAgeSeconds = Math.max(1L, window.toSeconds());

    String normalizedAcr = AuthzSubjectResolver.normalizeAcr(subject.acr());
    if (!StepUpRequiredException.REQUIRED_ACR.equals(normalizedAcr)) {
      throw new StepUpRequiredException(
          operationId, maxAgeSeconds, "Thao tác yêu cầu xác thực bước lên AAL2 (acr=aal2).");
    }

    Long authTime = subject.authTime();
    if (authTime == null) {
      throw new StepUpRequiredException(
          operationId, maxAgeSeconds, "Thiếu thời điểm xác thực (auth_time) cho thao tác AAL2.");
    }

    long nowEpochSeconds = clock.instant().getEpochSecond();
    long ageSeconds = nowEpochSeconds - authTime;
    if (ageSeconds > maxAgeSeconds || ageSeconds < -CLOCK_SKEW_TOLERANCE_SECONDS) {
      throw new StepUpRequiredException(
          operationId,
          maxAgeSeconds,
          "Phiên xác thực bước lên AAL2 đã quá hạn ("
              + ageSeconds
              + "s > "
              + maxAgeSeconds
              + "s).");
    }
  }

  private static UUID parseUuidOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
