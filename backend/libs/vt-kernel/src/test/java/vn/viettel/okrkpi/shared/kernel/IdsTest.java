package vn.viettel.okrkpi.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void sinhUuidPhienBan7BienTheRfc9562() {
    UUID id = Ids.newUuidV7();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    assertThat(Ids.isUuidV7(id)).isTrue();
  }

  @Test
  void uuidTangDanTheoThoiGianVaKhongTrung() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 20_000; i++) {
      ids.add(Ids.newUuidV7());
    }

    for (int i = 1; i < ids.size(); i++) {
      // Thứ tự theo chuỗi chính là thứ tự byte (cách PostgreSQL so sánh kiểu uuid).
      assertThat(ids.get(i).toString()).isGreaterThan(ids.get(i - 1).toString());
    }
    assertThat(new HashSet<>(ids)).hasSize(ids.size());
  }

  @Test
  void uuidSinhSauMotMiliGiayLonHonTheoThoiDiem() throws InterruptedException {
    UUID first = Ids.newUuidV7();
    Thread.sleep(3);
    UUID second = Ids.newUuidV7();

    assertThat(Ids.timestampOf(second)).isAfter(Ids.timestampOf(first));
    assertThat(second.toString()).isGreaterThan(first.toString());
  }

  @Test
  void thoiDiemTrongUuidGanVoiDongHoMay() {
    Instant before = Instant.now().minusMillis(1);
    UUID id = Ids.newUuidV7();
    Instant after = Instant.now().plusMillis(1);

    assertThat(Ids.timestampOf(id)).isBetween(before, after);
    assertThat(Duration.between(Ids.timestampOf(id), Instant.now()))
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void nhanDienUuidKhongPhaiPhienBan7() {
    UUID v4 = UUID.randomUUID();

    assertThat(Ids.isUuidV7(v4)).isFalse();
    assertThat(Ids.isUuidV7(null)).isFalse();
    assertThatThrownBy(() -> Ids.timestampOf(v4)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void docUuidTuChuoi() {
    assertThat(Ids.parse("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77"))
        .isEqualTo(UUID.fromString("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77"));
    assertThat(Ids.isUuidV7(Ids.parse("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77"))).isTrue();
    assertThatThrownBy(() -> Ids.parse("khong-phai-uuid"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ids.parse(null)).isInstanceOf(NullPointerException.class);
  }
}
