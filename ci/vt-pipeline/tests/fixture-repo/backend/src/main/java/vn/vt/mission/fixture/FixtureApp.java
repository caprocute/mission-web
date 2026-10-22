package vn.vt.mission.fixture;

import vn.vt.mission.fixture.application.CapNhatTienDo;
import vn.vt.mission.fixture.domain.TienDoNhiemVu;

/** Điểm vào của ảnh fixture (Jib mainClass). */
public final class FixtureApp {

  private FixtureApp() {}

  public static void main(String[] args) {
    TienDoNhiemVu tienDo = new CapNhatTienDo().thucHien(new TienDoNhiemVu(0), 100);
    System.out.println("fixture-service: hoàn thành = " + tienDo.hoanThanh());
  }
}
