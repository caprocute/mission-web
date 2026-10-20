package vn.viettel.okrkpi.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockTest {

  // 17:30 UTC ngày 31/12/2026 là 00:30 ngày 01/01/2027 giờ Việt Nam.
  private static final Instant CUOI_NAM_UTC = Instant.parse("2026-12-31T17:30:00Z");

  @Test
  void dongHoDungYenTraDungThoiDiem() {
    Clock clock = Clock.fixed(CUOI_NAM_UTC);

    assertThat(clock.instant()).isEqualTo(CUOI_NAM_UTC);
    assertThat(clock.nowUtc().getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(clock.nowUtc().toInstant()).isEqualTo(CUOI_NAM_UTC);
  }

  @Test
  void ngayNghiepVuTheoGioVietNam() {
    Clock clock = Clock.fixed(CUOI_NAM_UTC);

    assertThat(clock.today(ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 12, 31));
    assertThat(clock.businessDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(Clock.BUSINESS_ZONE).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
  }

  @Test
  void dongHoHeThongGanThoiDiemThat() {
    Instant now = Clock.systemUtc().instant();

    assertThat(Duration.between(now, Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void chuyenQuaLaiVoiJavaTimeClock() {
    java.time.Clock fixed = java.time.Clock.fixed(CUOI_NAM_UTC, ZoneOffset.UTC);
    Clock clock = Clock.of(fixed);

    java.time.Clock back = clock.toJavaClock();
    assertThat(back.instant()).isEqualTo(CUOI_NAM_UTC);
    assertThat(back.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(back.withZone(Clock.BUSINESS_ZONE).getZone()).isEqualTo(Clock.BUSINESS_ZONE);
    assertThat(back.withZone(Clock.BUSINESS_ZONE).instant()).isEqualTo(CUOI_NAM_UTC);
  }

  @Test
  void thayBangLambdaTrongKiemThu() {
    Instant[] now = {CUOI_NAM_UTC};
    Clock clock = () -> now[0];

    now[0] = now[0].plusSeconds(60);
    assertThat(clock.instant()).isEqualTo(CUOI_NAM_UTC.plusSeconds(60));
  }
}
