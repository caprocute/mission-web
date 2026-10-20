package vn.vt.mission.fixture.application;

import vn.vt.mission.fixture.domain.TienDoNhiemVu;

/** Ca sử dụng cập nhật tiến độ của fixture; lớp application chỉ phụ thuộc lớp domain. */
public final class CapNhatTienDo {

  public TienDoNhiemVu thucHien(TienDoNhiemVu hienTai, int moi) {
    return hienTai.capNhat(moi);
  }
}
