package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Shadow;
import vn.viettel.okrkpi.policy.domain.FeelUnaryTests.FeelException;

/**
 * FEEL đơn giản, dòng bị che, khoảng hở và đánh giá bảng xếp loại {@code xep-loai-dang} mẫu của
 * TL-01 trên các ca biên (CORE-05-05).
 */
class DecisionTableSpecTest {

  static final List<Column> COLUMNS =
      List.of(
          new Column("diem", "Điểm", "number"),
          new Column("ty_le_O", "Tỷ lệ O", "number"),
          new Column("ty_le_vuot", "Tỷ lệ vượt", "number"),
          new Column("truot_then_chot_chu_quan", "Trượt then chốt chủ quan", "boolean"));

  static Row row(String diem, String o, String vuot, String truot, String level) {
    Map<String, String> in = new HashMap<>();
    in.put("diem", diem);
    in.put("ty_le_O", o);
    in.put("ty_le_vuot", vuot);
    in.put("truot_then_chot_chu_quan", truot);
    return new Row(in, level, "QĐ Điều 11", "Giải thích {diem}");
  }

  /** Bảng {@code xep-loai-dang} của TL-01 "Ngôn ngữ biểu thức CEL và bảng quyết định DMN". */
  static DecisionTableSpec xepLoaiDang() {
    return new DecisionTableSpec(
        "FIRST",
        COLUMNS,
        List.of(
            row(">= 90", "1", ">= 0.30", "-", "HTXSNV"),
            row(">= 80", "-", "-", "false", "HTTNV"),
            row(">= 60", "-", "-", "-", "HTNV"),
            row("-", "-", "-", "-", "KHTNV")));
  }

  static Map<String, Object> ho(double diem, double o, double vuot, boolean truot) {
    return Map.of(
        "diem", diem, "ty_le_O", o, "ty_le_vuot", vuot, "truot_then_chot_chu_quan", truot);
  }

  @Test
  void xepLoaiDangGivesFourLevelsOnBoundaries() {
    DecisionTableSpec t = xepLoaiDang();
    assertThat(t.feelErrors()).isEmpty();
    assertThat(t.evaluate(ho(90, 1, 0.30, false))).contains("HTXSNV");
    assertThat(t.evaluate(ho(89.99, 1, 0.30, false))).contains("HTTNV");
    assertThat(t.evaluate(ho(92, 1, 0.29, false))).contains("HTTNV");
    assertThat(t.evaluate(ho(92, 0.99, 0.5, false))).contains("HTTNV");
    assertThat(t.evaluate(ho(95, 1, 0.20, true))).contains("HTNV");
    assertThat(t.evaluate(ho(80, 1, 0, false))).contains("HTTNV");
    assertThat(t.evaluate(ho(79.99, 1, 0, false))).contains("HTNV");
    assertThat(t.evaluate(ho(60, 1, 0, true))).contains("HTNV");
    assertThat(t.evaluate(ho(59.99, 1, 0, false))).contains("KHTNV");
    assertThat(t.shadowed()).isEmpty();
    assertThat(t.firstGap()).isEmpty();
  }

  @Test
  void parsesSimpleUnaryTests() {
    assertThat(FeelUnaryTests.parse("[0.6..0.9)", "number").matches(0.6)).isTrue();
    assertThat(FeelUnaryTests.parse("[0.6..0.9)", "number").matches(0.9)).isFalse();
    assertThat(FeelUnaryTests.parse("(1..2]", "number").matches(1)).isFalse();
    assertThat(FeelUnaryTests.parse("]1..2[", "number").matches(2)).isFalse();
    assertThat(FeelUnaryTests.parse("< 5, > 10", "number").matches(7)).isFalse();
    assertThat(FeelUnaryTests.parse("< 5, > 10", "number").matches(11)).isTrue();
    assertThat(FeelUnaryTests.parse("not(< 5, > 10)", "number").matches(7)).isTrue();
    assertThat(FeelUnaryTests.parse("not(< 5, > 10)", "number").matches(5)).isTrue();
    assertThat(FeelUnaryTests.parse("not(< 5, > 10)", "number").matches(10.5)).isFalse();
    assertThat(FeelUnaryTests.parse("not([1..2])", "number").matches(1.5)).isFalse();
    assertThat(FeelUnaryTests.parse("= -3", "number").matches(-3)).isTrue();
    assertThat(FeelUnaryTests.parse("<= 3", "number").matches("3")).isFalse();
    assertThat(FeelUnaryTests.parse("\"THUC_HIEN_TOT\",\"DAM_BAO\"", "string").matches("DAM_BAO"))
        .isTrue();
    assertThat(FeelUnaryTests.parse("not(\"A\")", "string").matches("B")).isTrue();
    assertThat(FeelUnaryTests.parse("\"a\\\"b\"", "string").matches("a\"b")).isTrue();
    assertThat(FeelUnaryTests.parse("true, false", "boolean").universal()).isTrue();
    assertThat(FeelUnaryTests.parse("not(true)", "boolean").matches(false)).isTrue();
    assertThat(FeelUnaryTests.parse(null, "boolean").universal()).isTrue();
    assertThat(FeelUnaryTests.parse(" - ", "string").matches("x")).isTrue();
  }

  @Test
  void reportsSyntaxAndTypeErrorsWithPosition() {
    for (String[] bad :
        new String[][] {
          {">= abc", "number", "4"},
          {"[1..", "number", "5"},
          {"[1 2]", "number", "4"},
          {"[3..1]", "number", "7"},
          {"[1..2", "number", "6"},
          {"\"x", "string", "3"},
          {"x", "string", "1"},
          {"yes", "boolean", "1"},
          {"not(true, false)", "boolean", "17"},
          {"1 2", "number", "3"},
          {"not(1", "number", "6"},
          {"", "date", "1"},
          {"1,", "number", "3"}
        }) {
      assertThatThrownBy(() -> FeelUnaryTests.parse(bad[0], bad[1]))
          .as(bad[0])
          .isInstanceOfSatisfying(
              FeelException.class,
              e -> assertThat(e.position()).as(bad[0]).isEqualTo(Integer.parseInt(bad[2])));
    }
    DecisionTableSpec t =
        new DecisionTableSpec("FIRST", COLUMNS, List.of(row(">= x", "-", "-", "-", "HTNV")));
    assertThat(t.feelErrors())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.row()).isEqualTo(1);
              assertThat(e.column()).isEqualTo("diem");
              assertThat(e.position()).isEqualTo(4);
            });
  }

  @Test
  void findsShadowedRowsAndGaps() {
    DecisionTableSpec t =
        new DecisionTableSpec(
            "FIRST",
            COLUMNS,
            List.of(
                row(">= 60", "-", "-", "-", "HTNV"),
                row(">= 80", "-", "-", "false", "HTTNV"),
                row("[0..60)", "-", "-", "-", "KHTNV"),
                row("[10..20]", "1", "-", "true", "KHTNV")));
    assertThat(t.shadowed()).containsExactly(new Shadow(2, 1), new Shadow(4, 3));
    assertThat(t.firstGap()).hasValueSatisfying(g -> assertThat(g.get("diem")).isNotNull());
    assertThat(t.evaluate(Map.of("diem", -5))).isEmpty();
    assertThat(new DecisionTableSpec("UNIQUE", COLUMNS, t.rows()).shadowed()).isEmpty();

    DecisionTableSpec strings =
        new DecisionTableSpec(
            "FIRST",
            List.of(new Column("muc", null, "string")),
            List.of(
                new Row(Map.of("muc", "not(\"KHONG_DAM_BAO\")"), "HTNV", "Đ", "G"),
                new Row(Map.of("muc", "\"DAM_BAO\""), "HTTNV", "Đ", "G"),
                new Row(Map.of("muc", "\"KHONG_DAM_BAO\""), "KHTNV", "Đ", "G"),
                new Row(Map.of(), "KHTNV", "Đ", "G")));
    assertThat(strings.shadowed()).containsExactly(new Shadow(2, 1));
    assertThat(strings.firstGap()).isEmpty();
  }

  @Test
  void priorityAndUniqueHitPolicies() {
    List<Row> rows =
        List.of(row(">= 60", "-", "-", "-", "HTNV"), row(">= 80", "-", "-", "-", "HTTNV"));
    assertThat(new DecisionTableSpec("PRIORITY", COLUMNS, rows).evaluate(ho(85, 1, 0, false)))
        .contains("HTTNV");
    assertThat(new DecisionTableSpec("UNIQUE", COLUMNS, rows).evaluate(ho(70, 1, 0, false)))
        .contains("HTNV");
    assertThatThrownBy(
            () -> new DecisionTableSpec("UNIQUE", COLUMNS, rows).evaluate(ho(85, 1, 0, false)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(DecisionTableSpec.templateVariables("Đạt {diem} theo { dieu_khoan }, {}"))
        .containsExactly("diem", "dieu_khoan", "");
    assertThat(DecisionTableSpec.templateVariables(null)).isEmpty();
  }
}
