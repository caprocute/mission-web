package vn.viettel.okrkpi.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectLocationTest {

  private static final UUID TENANT = UUID.fromString("01927a00-0000-7000-8000-00000000010a");

  @Test
  void bucketTheoCellVaTenantTrongGioiHanS3() {
    String bucket = ObjectLocation.bucketOf("HN-01", TENANT);
    assertThat(bucket).isEqualTo("ev-hn-01-" + TENANT);
    assertThat(bucket.length()).isLessThanOrEqualTo(63);
    assertThat(ObjectLocation.bucketOf("a".repeat(23), TENANT)).hasSize(63);
    assertThatThrownBy(() -> ObjectLocation.bucketOf("a".repeat(24), TENANT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectLocation.bucketOf("hn_01", TENANT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void khoaTheoNoiDung() {
    String hex = "AB" + "0".repeat(62);
    assertThat(ObjectLocation.keyOf(TENANT, hex))
        .isEqualTo("t/" + TENANT + "/sha256/ab/ab" + "0".repeat(62));
    byte[] sha = new byte[32];
    sha[0] = (byte) 0xcd;
    assertThat(ObjectLocation.keyOf(TENANT, sha)).startsWith("t/" + TENANT + "/sha256/cd/cd00");
    assertThatThrownBy(() -> ObjectLocation.keyOf(TENANT, "abc"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ObjectLocation.keyOf(TENANT, new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void viTriDayDu() {
    byte[] sha = new byte[32];
    ObjectLocation loc = ObjectLocation.of("dev", TENANT, sha);
    assertThat(loc.bucket()).isEqualTo("ev-dev-" + TENANT);
    assertThat(loc.key()).endsWith("/sha256/00/" + "0".repeat(64));
    assertThatThrownBy(() -> ObjectLocation.keyOf(TENANT, (byte[]) null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObjectLocation(null, "k"))
        .isInstanceOf(NullPointerException.class);
  }
}
