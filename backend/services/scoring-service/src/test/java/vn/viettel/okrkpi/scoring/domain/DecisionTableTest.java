package vn.viettel.okrkpi.scoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionTableTest {

  @Test
  void oFeelDangHoTro() {
    assertThat(UnaryTest.matches("-", null)).isTrue();
    assertThat(UnaryTest.matches(null, 1)).isTrue();
    assertThat(UnaryTest.matches(">= 90", 90)).isTrue();
    assertThat(UnaryTest.matches(">= 90", 89.9)).isFalse();
    assertThat(UnaryTest.matches("> 0.3", 0.3)).isFalse();
    assertThat(UnaryTest.matches("< 60", 59.9)).isTrue();
    assertThat(UnaryTest.matches("<= 60", 60)).isTrue();
    assertThat(UnaryTest.matches("< 60", null)).isFalse();
    assertThat(UnaryTest.matches("1", 1.0)).isTrue();
    assertThat(UnaryTest.matches("1", "1")).isFalse();
    assertThat(UnaryTest.matches("true", true)).isTrue();
    assertThat(UnaryTest.matches("false", "false")).isFalse();
    assertThat(UnaryTest.matches("\"THUC_HIEN_TOT\",\"DAM_BAO\"", "DAM_BAO")).isTrue();
    assertThat(UnaryTest.matches("\"A,B\"", "A,B")).isTrue();
    assertThat(UnaryTest.matches("[0.9..1)", 0.9)).isTrue();
    assertThat(UnaryTest.matches("[0.9..1)", 1)).isFalse();
    assertThat(UnaryTest.matches("(0.5..0.9]", 0.5)).isFalse();
    assertThat(UnaryTest.matches("(0.5..0.9]", 0.9)).isTrue();
    assertThat(UnaryTest.matches("[0..0.5), [0.9..1]", 1)).isTrue();
    assertThat(UnaryTest.matches("[0..1]", null)).isFalse();
    assertThatThrownBy(() -> UnaryTest.matches("not(1)", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nguongCuaO() {
    assertThat(UnaryTest.threshold(">= 90")).isEqualByComparingTo(BigDecimal.valueOf(90));
    assertThat(UnaryTest.threshold("[0.9..1)")).isEqualByComparingTo(new BigDecimal("0.9"));
    assertThat(UnaryTest.threshold("80")).isEqualByComparingTo(BigDecimal.valueOf(80));
    assertThat(UnaryTest.threshold("-")).isNull();
    assertThat(UnaryTest.threshold(null)).isNull();
  }

  @Test
  void dongVaChuThich() {
    DecisionTable.Rule rule =
        new DecisionTable.Rule(
            "dong-1",
            "mô tả",
            List.of(">= 90"),
            " \"HTXSNV\" ",
            Map.of("giai_thich", " Câu ", "mau_ha_muc", "-", "trong", " "));
    DecisionTable table =
        new DecisionTable(
            "xep_loai",
            "FIRST",
            List.of(new DecisionTable.Column("diem", "Điểm", "number")),
            List.of(rule));
    assertThat(table.rules().get(0).outputValue()).isEqualTo("HTXSNV");
    assertThat(rule.annotation("giai_thich")).isEqualTo("Câu");
    assertThat(rule.annotation("mau_ha_muc")).isNull();
    assertThat(rule.annotation("trong")).isNull();
    assertThat(rule.annotation("khong_co")).isNull();
    assertThat(new DecisionTable.Rule("d", null, List.of(), "1", Map.of()).outputValue())
        .isEqualTo("1");
    assertThat(new DecisionTable.Rule("d", null, List.of(), null, Map.of()).outputValue()).isNull();
    assertThatThrownBy(() -> new DecisionTable(null, "FIRST", List.of(), List.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
