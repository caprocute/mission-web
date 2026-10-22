package vn.viettel.okrkpi.okr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** So nội dung lưu với đầu ra của gợi ý AI (CORE-01-13). */
class AiContentDiffTest {

  static Map<String, Object> kr(String title, String unit, Object target) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("title", title);
    m.put("unit", unit);
    m.put("baselineValue", BigDecimal.ZERO);
    m.put("targetValue", target);
    return m;
  }

  @Test
  void mucTieuSoTenSauChuanHoa() {
    var out = Map.<String, Object>of("objective", Map.of("title", "Nâng  chất lượng"));
    assertThat(AiContentDiff.objective(out, " Nâng chất lượng ").contentSource())
        .isEqualTo(AiContentDiff.AI_ACCEPTED);
    assertThat(AiContentDiff.objective(Map.of("text", "Khác"), "Nâng").edited())
        .containsExactly("title");
    // Gợi ý không có tên đề xuất: coi như đã sửa toàn bộ.
    assertThat(AiContentDiff.objective(Map.of(), "Nâng").edited()).containsExactly("title");
    assertThat(AiContentDiff.objective(null, "Nâng").contentSource())
        .isEqualTo(AiContentDiff.AI_EDITED);
  }

  @Test
  void krChonDeXuatGanNhatVaSoTheoGiaTri() {
    Map<String, Object> out =
        Map.of(
            "objective",
            Map.of(
                "keyResults",
                List.of(
                    Map.of("title", "Số chuyên đề", "unit", "chuyên đề"),
                    Map.of("title", "Số chi bộ đạt chuẩn", "unit", "chi bộ", "targetValue", 10.0),
                    "rác")));
    var r = AiContentDiff.keyResult(out, kr("Số chi bộ đạt chuẩn", "chi bộ", new BigDecimal("10")));
    assertThat(r.kept()).containsExactly("title", "unit", "targetValue");
    assertThat(r.edited()).isEmpty();
    var edited = AiContentDiff.keyResult(out, kr("Số chi bộ đạt chuẩn", null, 12));
    assertThat(edited.edited()).containsExactly("unit", "targetValue");
    var text = AiContentDiff.keyResult(Map.of("text", "Viết lại KR"), kr("Viết lại KR", "x", 1));
    assertThat(text.kept()).containsExactly("title");
    assertThat(AiContentDiff.keyResult(Map.of(), kr("A", "x", 1)).edited()).hasSize(4);
  }

  @Test
  void soSanhGiaTriRong() {
    assertThat(AiContentDiff.same(null, null)).isTrue();
    assertThat(AiContentDiff.same(null, "a")).isFalse();
    assertThat(AiContentDiff.same("2", BigDecimal.ONE)).isFalse();
    assertThat(AiContentDiff.similarity("", "")).isEqualTo(1.0);
    assertThat(AiContentDiff.similarity("a b", "b c")).isEqualTo(1.0 / 3);
  }
}
