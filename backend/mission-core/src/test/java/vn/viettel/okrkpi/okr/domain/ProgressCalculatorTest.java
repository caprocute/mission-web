package vn.viettel.okrkpi.okr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.okr.domain.ProgressCalculator.Weighted;

/** Công thức tiến độ TL-02a L858: KR = (hiện tại − đầu)/(chỉ tiêu − đầu) kẹp [0; 1]. */
class ProgressCalculatorTest {

  private static BigDecimal kr(String type, String dir, String base, String target, String cur) {
    return ProgressCalculator.keyResult(type, dir, d(base), d(target), d(cur));
  }

  private static BigDecimal d(String v) {
    return v == null ? null : new BigDecimal(v);
  }

  @Test
  void chieuTangTinhTheoDuongCoSo() {
    assertThat(kr("SO_LUONG", "TANG", "20", "120", "70")).isEqualByComparingTo("0.5");
    assertThat(kr("TY_LE", "TANG", null, "95", "38")).isEqualByComparingTo("0.4");
    assertThat(kr("SO_LUONG", "TANG", "0", "3", "1")).isEqualByComparingTo("0.3333");
  }

  @Test
  void vuotChiTieuVaDuoiDuongCoSoBiKep() {
    assertThat(kr("SO_LUONG", "TANG", "0", "100", "150")).isEqualByComparingTo("1");
    assertThat(kr("SO_LUONG", "TANG", "50", "100", "10")).isEqualByComparingTo("0");
    assertThat(kr("SO_LUONG", "GIAM", "10", "2", "0")).isEqualByComparingTo("1");
  }

  @Test
  void chieuGiamTinhNguoc() {
    assertThat(kr("SO_LUONG", "GIAM", "10", "2", "6")).isEqualByComparingTo("0.5");
    assertThat(kr("SO_LUONG", "GIAM", "10", "2", "12")).isEqualByComparingTo("0");
    // Không có đường cơ sở hợp lệ: chỉ đạt hoặc chưa đạt.
    assertThat(kr("SO_LUONG", "GIAM", null, "2", "2")).isEqualByComparingTo("1");
    assertThat(kr("SO_LUONG", "GIAM", "1", "2", "3")).isEqualByComparingTo("0");
  }

  @Test
  void chieuDatDuChiTieuLaHoanThanh() {
    assertThat(kr("CHI_SO", "DAT", "0", "80", "80")).isEqualByComparingTo("1");
    assertThat(kr("CHI_SO", "DAT", "0", "80", "40")).isEqualByComparingTo("0.5");
  }

  @Test
  void loaiCoKhongChiCoHaiMuc() {
    assertThat(kr("CO_KHONG", "TANG", null, "1", "1")).isEqualByComparingTo("1");
    assertThat(kr("CO_KHONG", "TANG", null, "1", "0")).isEqualByComparingTo("0");
  }

  @Test
  void chuaCheckInLaKhong() {
    assertThat(kr("SO_LUONG", "TANG", "0", "100", null)).isEqualByComparingTo("0");
    assertThat(kr("SO_LUONG", "TANG", "0", null, "5")).isEqualByComparingTo("0");
    assertThat(kr("SO_LUONG", "TANG", "5", "5", "4")).isEqualByComparingTo("0");
    assertThat(kr("SO_LUONG", "TANG", "5", "5", "5")).isEqualByComparingTo("1");
  }

  @Test
  void tienDoMucTieuLaTrungBinhCoTrongSo() {
    assertThat(
            ProgressCalculator.objective(
                List.of(
                    new Weighted(d("0.6"), d("0.5")),
                    new Weighted(d("0.4"), d("1")),
                    new Weighted(d("0"), d("1")),
                    new Weighted(null, d("1")))))
        .isEqualByComparingTo("0.7");
    assertThat(ProgressCalculator.objective(List.of())).isEqualByComparingTo("0");
    assertThat(ProgressCalculator.objective(List.of(new Weighted(d("0.3"), null))))
        .isEqualByComparingTo("0");
  }
}
