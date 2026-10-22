package vn.viettel.okrkpi.esign.spi;

import java.time.Instant;
import java.util.Objects;

/** Phiên ký đã mở ở nhà cung cấp; {@code expiresAt} là hạn xác nhận phía nhà cung cấp. */
public record SignSession(String sessionId, Instant expiresAt) {
  public SignSession {
    Objects.requireNonNull(sessionId, "sessionId");
  }
}
