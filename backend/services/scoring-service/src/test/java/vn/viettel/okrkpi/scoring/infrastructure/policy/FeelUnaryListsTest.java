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
  void oKhoangThanhBieuThucVa() {
    assertThat(FeelUnaryLists.expression("t", "[0.9..1)")).isEqualTo("${t >= 0.9 && t < 1}");
    assertThat(FeelUnaryLists.expression("t", "]0..60]")).isEqualTo("${t > 0 && t <= 60}");
    assertThat(FeelUnaryLists.expression("t", "(-1 .. 2.5]")).isEqualTo("${t > -1 && t <= 2.5}");
  }

  @Test
  void bienDauVaoCuaBangDauTien() {
    String xml =
        "<definitions><decision><decisionTable><input><inputExpression><text> a </text>"
            + "</inputExpression></input><input/></decisionTable></decision></definitions>";
    assertThat(FeelUnaryLists.inputs(xml)).containsExactly("a");
    assertThat(FeelUnaryLists.inputs("<definitions/>")).isEmpty();
    assertThat(FeelUnaryLists.inputs("<khong")).isEmpty();
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
