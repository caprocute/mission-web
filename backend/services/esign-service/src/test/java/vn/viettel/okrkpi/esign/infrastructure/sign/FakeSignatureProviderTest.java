package vn.viettel.okrkpi.esign.infrastructure.sign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.spi.SignedItem;

class FakeSignatureProviderTest {

  private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

  private final FakeSignatureProvider provider =
      new FakeSignatureProvider(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1));

  @Test
  void moiNguoiKyCoMotChungThuConHieuLucRieng() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    SignedItem.Certificate ca = provider.certificates(UUID.randomUUID(), a).get(0);
    SignedItem.Certificate cb = provider.certificates(UUID.randomUUID(), b).get(0);
    assertThat(ca.notAfter()).isAfter(NOW);
    assertThat(ca.ocspStatus()).isEqualTo("GOOD");
    assertThat(ca.serialNo()).contains(a.toString());
    assertThat(ca).isNotEqualTo(cb);
  }
}
