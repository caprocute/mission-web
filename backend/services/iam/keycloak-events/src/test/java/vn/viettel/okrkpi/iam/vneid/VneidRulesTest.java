package vn.viettel.okrkpi.iam.vneid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Quy tắc thuần của môi giới VNeID (DV-02-04): mức tài khoản, băm mã chủ thể, bộ ngắt mạch, tham số triển khai. */
class VneidRulesTest {

  static final byte[] KEY = new byte[32];

  @Test
  void mucTaiKhoanTuHaiTroLenMoiDuoc() {
    assertThat(VneidIdentity.levelAccepted("2", 2)).isTrue();
    assertThat(VneidIdentity.levelAccepted("muc2", 2)).isTrue();
    assertThat(VneidIdentity.levelAccepted("3", 2)).isTrue();
    assertThat(VneidIdentity.levelAccepted("1", 2)).isFalse();
    assertThat(VneidIdentity.levelAccepted("level-1", 2)).isFalse();
    assertThat(VneidIdentity.levelAccepted(null, 2)).isFalse();
    assertThat(VneidIdentity.levelAccepted("cao", 2)).isFalse();
    assertThat(VneidIdentity.levelAccepted("99999", 2)).isFalse();
  }

  @Test
  void claimNhanSoVaChuoi() {
    Map<String, Object> c = new HashMap<>();
    c.put("muc", 2);
    c.put("rong", " ");
    assertThat(VneidIdentity.claim(c, "muc")).isEqualTo("2");
    assertThat(VneidIdentity.claim(c, "rong")).isNull();
    assertThat(VneidIdentity.claim(c, "thieu")).isNull();
    assertThat(VneidIdentity.claim(null, "muc")).isNull();
    assertThat(VneidIdentity.claim(c, " ")).isNull();
  }

  @Test
  void bamMaChuTheOnDinhTheoRealmVaKhongLoMaGoc() {
    String a = VneidIdentity.hashedSubject(KEY, "okrkpi", "001099012345");
    assertThat(a).matches("[0-9a-f]{32}").doesNotContain("001099012345");
    assertThat(VneidIdentity.hashedSubject(KEY, "okrkpi", "001099012345")).isEqualTo(a);
    assertThat(VneidIdentity.hashedSubject(KEY, "okrkpi-khac", "001099012345")).isNotEqualTo(a);
    byte[] other = KEY.clone();
    other[0] = 1;
    assertThat(VneidIdentity.hashedSubject(other, "okrkpi", "001099012345")).isNotEqualTo(a);
    assertThatThrownBy(() -> VneidIdentity.hashedSubject(new byte[16], "okrkpi", "x"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> VneidIdentity.hashedSubject(null, "okrkpi", "x"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void boNgatMachMoSauNguongVaNuaMoKhiHetHan() {
    AtomicLong now = new AtomicLong(1_000);
    CircuitBreaker b = new CircuitBreaker(2, 30_000, now::get);
    assertThat(b.allow()).isTrue();
    b.failure();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    b.success();
    b.failure();
    assertThat(b.allow()).isTrue();
    b.failure();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(b.allow()).isFalse();
    now.addAndGet(30_000);
    assertThat(b.allow()).isTrue();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    b.failure();
    assertThat(b.allow()).isFalse();
    now.addAndGet(30_000);
    assertThat(b.allow()).isTrue();
    b.success();
    assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void boNgatDungChungTheoKhoaVaTaoLaiKhiDoiCauHinh() {
    CircuitBreaker a = CircuitBreaker.of("realm/vneid-kiem-thu", 5, 30_000);
    assertThat(CircuitBreaker.of("realm/vneid-kiem-thu", 5, 30_000)).isSameAs(a);
    assertThat(CircuitBreaker.of("realm/vneid-kiem-thu", 2, 30_000)).isNotSameAs(a);
    assertThat(CircuitBreaker.of("realm-khac/vneid-kiem-thu", 2, 30_000)).isNotSameAs(a);
  }

  @Test
  void thamSoTrienKhaiDocTuMoiTruong() {
    byte[] key = new byte[32];
    key[3] = 7;
    VneidSettings s = VneidSettings.from(Map.of(
        "MISSION_VNEID_LINK_KEY", Base64.getEncoder().encodeToString(key),
        "MISSION_ORG_SERVICE_URL", "http://org-service:8080//",
        "MISSION_IAM_BROKER_TOKEN_URL", "http://keycloak/token",
        "MISSION_IAM_BROKER_CLIENT_SECRET", "bi-mat-kiem-thu",
        "MISSION_IAM_BROKER_TIMEOUT_MS", "1500"));
    assertThat(s.linkKey()).isEqualTo(key);
    assertThat(s.orgServiceUrl()).isEqualTo("http://org-service:8080");
    assertThat(s.brokerClientId()).isEqualTo("mission-iam-broker");
    assertThat(s.orgTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(s.toString()).doesNotContain("bi-mat-kiem-thu");

    VneidSettings empty = VneidSettings.from(Map.of("MISSION_VNEID_LINK_KEY", "khong-phai-base64-%%"));
    assertThat(empty.linkKey()).isNotEmpty();
    assertThat(empty.orgServiceUrl()).isEmpty();
    assertThat(empty.orgTimeout()).isEqualTo(Duration.ofMillis(3000));
    assertThat(VneidSettings.from(Map.of()).linkKey()).isNull();
  }
}
