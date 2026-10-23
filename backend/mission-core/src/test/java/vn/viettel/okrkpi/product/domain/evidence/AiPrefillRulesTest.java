package vn.viettel.okrkpi.product.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CORE-03-05 · BR-07-09: kiểm ô do AI điền khi lưu nháp. */
class AiPrefillRulesTest {

  static String field(Runnable r) {
    try {
      r.run();
      return null;
    } catch (AiPrefillRules.Violation e) {
      return e.field();
    }
  }

  @Test
  void khongCoOAiThiKhongGhiOSua() {
    assertThat(AiPrefillRules.check(false, false, null, true)).isEmpty();
    assertThat(AiPrefillRules.check(false, true, List.of(), false)).isEmpty();
    assertThat(field(() -> AiPrefillRules.check(false, true, List.of("docDate"), false)))
        .isEqualTo("aiFieldsEdited");
  }

  @Test
  void tepC3KhongMangOAiVaPhaiCoMaGoiY() {
    assertThat(field(() -> AiPrefillRules.check(true, true, List.of(), true)))
        .isEqualTo("aiPrefill");
    assertThat(field(() -> AiPrefillRules.check(true, false, List.of(), false)))
        .isEqualTo("aiSuggestionId");
    assertThat(field(() -> AiPrefillRules.check(true, true, List.of("title"), false)))
        .isEqualTo("aiFieldsEdited");
  }

  @Test
  void chuanHoaOSuaVaTinhOGiu() {
    List<String> edited =
        AiPrefillRules.check(true, true, List.of("taskId", "docNumber", "taskId"), false);
    assertThat(edited).containsExactly("docNumber", "taskId");
    assertThat(AiPrefillRules.kept(edited)).containsExactly("docDate", "docSummary");
    assertThat(AiPrefillRules.kept(null)).isEqualTo(AiPrefillRules.AI_FIELDS);
    assertThat(AiPrefillRules.check(true, true, null, false)).isEmpty();
    assertThatThrownBy(() -> AiPrefillRules.kept(edited).add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
