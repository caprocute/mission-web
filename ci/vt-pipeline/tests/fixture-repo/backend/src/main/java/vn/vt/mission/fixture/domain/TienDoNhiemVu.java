package vn.vt.mission.fixture.domain;

/**
 * Tiến độ nhiệm vụ 0..100 (thang của MOD-02 theo hợp đồng). Lớp miền tối thiểu của fixture vt-pipeline,
 * chỉ để có mã thật cho kiểm thử đơn vị, ArchUnit và JaCoCo.
 */
public record TienDoNhiemVu(int phanTram) {

  public TienDoNhiemVu {
    if (phanTram < 0 || phanTram > 100) {
      throw new IllegalArgumentException("Tiến độ phải trong khoảng 0..100");
    }
  }

  /** Cập nhật tiến độ chỉ ghi thêm: không cho lùi tiến độ. */
  public TienDoNhiemVu capNhat(int moi) {
    if (moi < phanTram) {
      throw new IllegalStateException("Không được giảm tiến độ đã báo cáo");
    }
    return new TienDoNhiemVu(moi);
  }

  public boolean hoanThanh() {
    return phanTram == 100;
  }
}
