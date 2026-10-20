package vn.vt.mission.fixture.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vn.vt.mission.fixture.FixtureApp;
import vn.vt.mission.fixture.application.CapNhatTienDo;

class TienDoNhiemVuTest {

  @Test
  void capNhatTangTienDo() {
    TienDoNhiemVu moi = new CapNhatTienDo().thucHien(new TienDoNhiemVu(40), 60);
    assertEquals(60, moi.phanTram());
    assertFalse(moi.hoanThanh());
  }

  @Test
  void hoanThanhKhiDat100() {
    assertTrue(new TienDoNhiemVu(100).hoanThanh());
  }

  @Test
  void khongChoGiamTienDo() {
    assertThrows(IllegalStateException.class, () -> new TienDoNhiemVu(50).capNhat(10));
  }

  @Test
  void tuChoiGiaTriNgoaiKhoang() {
    assertThrows(IllegalArgumentException.class, () -> new TienDoNhiemVu(101));
    assertThrows(IllegalArgumentException.class, () -> new TienDoNhiemVu(-1));
  }

  @Test
  void diemVaoChayDuoc() {
    FixtureApp.main(new String[0]);
  }
}
