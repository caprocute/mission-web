package vn.viettel.okrkpi.scoring.infrastructure.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeelUnaryListsTest {

  @Test
  void oDanhSachThanhBieuThucHoac() {
    assertThat(FeelUnaryLists.expression("m", "\"A\",\"B, C\""))
        .isEqualTo("${m == \"A\" || m == \"B, C\"}");
    assertThat(FeelUnaryLists.expression("d", "< 60, >= 90.5, 75"))
        .isEqualTo("${d < 60 || d >= 90.5 || d == 75}");
    assertThat(FeelUnaryLists.expression("d", "\"A\"")).isNull();
    assertThat(FeelUnaryLists.expression("d", "[1..2], 3")).isNull();
  }

  @Test
  void xmlKhongDoiHoacHongGiuNguyen() {
    assertThat(FeelUnaryLists.rewrite("<khong")).isEqualTo("<khong");
    String plain = "<definitions><decision/></definitions>";
    assertThat(FeelUnaryLists.rewrite(plain)).isEqualTo(plain);
    assertThat(FeelUnaryLists.rewrite("<!DOCTYPE x [<!ENTITY e \"v\">]><x>&e;</x>"))
        .startsWith("<!DOCTYPE");
  }
}
