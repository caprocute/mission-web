package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tương phản WCAG 2.1 trên bộ ca dùng chung với WEB-004 (CORE-08-10). */
class WcagContrastGoldenTest {

  static final Path CASES = Path.of("../../data/golden/ui-pack-checks/tuong-phan.json");

  @Test
  void matchesSharedCases() throws Exception {
    JsonNode cases = new ObjectMapper().readTree(Files.readString(CASES)).path("cases");
    assertThat(cases.size()).isGreaterThanOrEqualTo(6);
    for (JsonNode c : cases) {
      double ratio = WcagContrast.ratio(c.get("text").asText(), c.get("background").asText());
      String id = c.get("id").asText();
      assertThat(ratio).as(id).isCloseTo(c.get("ratio").asDouble(), within(0.001));
      assertThat(ratio >= WcagContrast.AA).as(id).isEqualTo(c.get("aa").asBoolean());
      assertThat(ratio >= WcagContrast.ELDERLY).as(id).isEqualTo(c.get("elderly").asBoolean());
    }
  }

  @Test
  void formatsDownAndRejectsUnreadableColors() {
    assertThat(WcagContrast.format(4.478)).isEqualTo("4,47");
    assertThat(WcagContrast.ratio("#0a5fa8ff", "#FFF")).isCloseTo(6.531, within(0.001));
    for (String bad : new String[] {"#0A5FA880", "red", "rgb(300, 0, 0)", "#12", null}) {
      assertThat(WcagContrast.parsable(bad)).as(String.valueOf(bad)).isFalse();
    }
    assertThatThrownBy(() -> WcagContrast.ratio("blue", "#FFF"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
