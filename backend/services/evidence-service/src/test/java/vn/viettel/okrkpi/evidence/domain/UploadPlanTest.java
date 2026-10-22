package vn.viettel.okrkpi.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UploadPlanTest {

  private static final int MB = 1024 * 1024;

  @Test
  void chiaPhanVaPhanCuoiNhoHon() {
    UploadPlan p = new UploadPlan(17L * MB + 3, 8 * MB);
    assertThat(p.partCount()).isEqualTo(3);
    assertThat(p.expectedSize(1)).isEqualTo(8L * MB);
    assertThat(p.expectedSize(3)).isEqualTo(MB + 3);
    assertThat(p.expectedSize(0)).isEqualTo(-1);
    assertThat(p.expectedSize(4)).isEqualTo(-1);
    assertThat(new UploadPlan(100, 5 * MB).partCount()).isEqualTo(1);
  }

  @Test
  void thamSoSai() {
    assertThatThrownBy(() -> new UploadPlan(0, 8 * MB))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UploadPlan(1, 4 * MB))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UploadPlan(1, 17 * MB))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void viTriTamVaKieuTep() {
    assertThat(ObjectLocation.stagingBucketOf("HN-01")).isEqualTo("ev-hn-01-tam");
    assertThat(FileKind.ofMediaType("image/png")).contains(FileKind.PNG);
    assertThat(FileKind.ofMediaType("application/zip")).isEmpty();
    assertThatThrownBy(() -> ObjectLocation.stagingBucketOf("a_b"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
