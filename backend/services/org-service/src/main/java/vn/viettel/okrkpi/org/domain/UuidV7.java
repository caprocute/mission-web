package vn.viettel.okrkpi.org.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** UUIDv7 (RFC 9562): 48 bit đầu là mili giây, phần còn lại ngẫu nhiên (TL-03 "Cột chung"). */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {
  }

  public static UUID next(Clock clock) {
    long millis = clock.millis();
    long randA = RANDOM.nextInt(1 << 12);
    long msb = (millis << 16) | (0x7L << 12) | randA;
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
