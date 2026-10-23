package vn.viettel.okrkpi.integ.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IntegDomainTest {

  static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

  @Test
  void lichThuLaiNhanDoiDenTranRoiHetLuot() {
    RetrySchedule s = new RetrySchedule(Duration.ofSeconds(2), 2, Duration.ofSeconds(5), 5);
    assertThat(s.next(1, NOW)).contains(NOW.plusSeconds(2));
    assertThat(s.next(2, NOW)).contains(NOW.plusSeconds(4));
    assertThat(s.next(3, NOW)).contains(NOW.plusSeconds(5));
    assertThat(s.next(4, NOW)).contains(NOW.plusSeconds(5));
    assertThat(s.next(5, NOW)).isEmpty();
    assertThat(RetrySchedule.DEFAULT.maxAttempts()).isEqualTo(5);
    assertThatThrownBy(() -> new RetrySchedule(Duration.ZERO, 2, Duration.ofSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetrySchedule(Duration.ofSeconds(1), 0, Duration.ofSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loiChiGhiTenLopKhongGhiNoiDung() {
    Exception e = new IllegalStateException("bí mật", new IOException("số CCCD 0123"));
    assertThat(ErrorText.code(e)).isEqualTo("IllegalStateException");
    assertThat(ErrorText.detail(e))
        .isEqualTo("java.lang.IllegalStateException <- java.io.IOException")
        .doesNotContain("bí mật", "0123");
    assertThat(ErrorText.code(null)).isEqualTo("KHONG_RO");
    assertThat(ErrorText.detail(null)).isNotBlank();
    assertThat(ErrorText.code(new RuntimeException() {})).isEqualTo("KHONG_RO");
  }

  @Test
  void thongDiepVaoKiemTraVaSaoChepPhongVe() {
    byte[] raw = "nội dung".getBytes(StandardCharsets.UTF_8);
    InboundMessage m = new InboundMessage("VB-1", "VAN_BAN", raw);
    raw[0] = 0;
    assertThat(m.raw()[0]).isNotEqualTo((byte) 0);
    m.raw()[1] = 0;
    assertThat(m)
        .isEqualTo(
            new InboundMessage("VB-1", "VAN_BAN", "nội dung".getBytes(StandardCharsets.UTF_8)));
    assertThat(m.hashCode())
        .isEqualTo(
            new InboundMessage("VB-1", "VAN_BAN", "nội dung".getBytes(StandardCharsets.UTF_8))
                .hashCode());
    assertThat(m.toString()).doesNotContain("nội dung");
    assertThatThrownBy(() -> new InboundMessage(" ", "VAN_BAN", raw))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InboundMessage("VB-1", "x".repeat(49), raw))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chieuKetNoi() {
    assertThat(Direction.HAI_CHIEU.inbound()).isTrue();
    assertThat(Direction.HAI_CHIEU.outbound()).isTrue();
    assertThat(Direction.VAO.outbound()).isFalse();
    assertThat(Direction.RA.inbound()).isFalse();
  }
}
