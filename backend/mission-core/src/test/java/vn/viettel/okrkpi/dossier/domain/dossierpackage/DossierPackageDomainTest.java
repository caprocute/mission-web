package vn.viettel.okrkpi.dossier.domain.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** CORE-06-06: mã gói, loại gói theo kỳ và trạng thái gói theo tiến độ sinh tệp. */
class DossierPackageDomainTest {

  @Test
  void maGoiTheoNamVaLoai() {
    assertThat(PackageCode.of(2026, PackageKind.NAM, 1)).isEqualTo("DP-2026-N-0001");
    assertThat(PackageCode.of(2026, PackageKind.QUY, 42)).isEqualTo("DP-2026-Q-0042");
    assertThat(PackageCode.prefix(2026, PackageKind.DIEN_TW)).isEqualTo("DP-2026-N-");
    assertThat(PackageCode.PATTERN.matcher(PackageCode.of(2026, PackageKind.QUY, 9999)).matches())
        .isTrue();
    assertThat(PackageCode.yearOf(20263)).isEqualTo(2026);
    assertThatThrownBy(() -> PackageCode.of(2026, PackageKind.NAM, 10000))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> PackageCode.of(2026, PackageKind.NAM, 0))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> PackageCode.prefix(99, PackageKind.NAM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loaiGoiTheoKhoaPhanVung() {
    assertThat(PackageKind.ofBucket(20260)).isEqualTo(PackageKind.NAM);
    assertThat(PackageKind.ofBucket(20262)).isEqualTo(PackageKind.QUY);
    assertThat(PackageKind.QUY.codeLetter()).isEqualTo('Q');
    assertThat(PackageKind.NAM.codeLetter()).isEqualTo('N');
  }

  @Test
  void trangThaiGoiTheoTienDoSinhTep() {
    assertThat(new RenderProgress(0, 0, 0, 0).next()).isEqualTo(PackageStatus.DANG_LAP);
    RenderProgress generating = new RenderProgress(4, 2, 1, 0);
    assertThat(generating.pending()).isEqualTo(1);
    assertThat(generating.next()).isEqualTo(PackageStatus.DANG_SINH);
    assertThat(new RenderProgress(4, 3, 1, 0).next()).isEqualTo(PackageStatus.DANG_LAP);
    assertThat(new RenderProgress(4, 4, 0, 0).next()).isEqualTo(PackageStatus.CHO_KY);
  }

  @Test
  void goiDaKyBiKhoaVaTepDaSinh() {
    assertThat(PackageStatus.DA_KY.locked()).isTrue();
    assertThat(PackageStatus.CHO_KY.locked()).isFalse();
    assertThat(FormStatus.DA_SINH.rendered()).isTrue();
    assertThat(FormStatus.LOI.rendered()).isFalse();
  }
}
