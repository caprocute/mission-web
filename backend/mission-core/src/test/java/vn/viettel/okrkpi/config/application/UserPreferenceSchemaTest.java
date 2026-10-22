package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** Kiểm giá trị theo user-preference.schema.json (CNH-02). */
class UserPreferenceSchemaTest {

  private final ObjectMapper json = new ObjectMapper();

  private JsonNode tree(String s) throws Exception {
    return json.readTree(s);
  }

  @Test
  void acceptsEveryKindOfValidPreference() throws Exception {
    assertThatCode(
            () -> {
              UserPreferenceSchema.requireValidPreference(
                  "ui.display",
                  tree(
                      "{\"mode\":\"elderly\",\"reducedSteps\":true,\"fontScale\":1.1,"
                          + "\"density\":\"comfortable\",\"highContrastStatus\":false}"));
              UserPreferenceSchema.requireValidPreference(
                  "ui.table.SCR-0201",
                  tree(
                      "{\"visibleColumns\":[\"code\",\"dueDate\"],\"pageSize\":50,"
                          + "\"columnWidths\":{\"code\":120},"
                          + "\"defaultSort\":{\"field\":\"dueDate\",\"direction\":\"ASC\"}}"));
              UserPreferenceSchema.requireValidPreference(
                  "ui.dashboard.default",
                  tree("{\"pinnedKpiCodes\":[\"KPI_01\"],\"defaultPeriodType\":\"QUY\"}"));
              UserPreferenceSchema.requireValidPreference(
                  "notify.channel_pref",
                  tree(
                      "{\"quietHours\":{\"enabled\":true,\"start\":\"22:00\",\"end\":\"06:30\"},"
                          + "\"digestMode\":\"DAILY_SUMMARY\"}"));
              UserPreferenceSchema.requireValidCriteria(
                  tree(
                      "{\"ratingCodes\":[\"HTNV\"],\"numberRanges\":{\"progress\":{\"min\":0,\"max\":50}},"
                          + "\"dateRanges\":{\"dueDate\":{\"from\":\"2026-10-01\"}},\"flags\":{\"late\":true}}"));
            })
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsOutOfSchemaValues() throws Exception {
    JsonNode badScale = tree("{\"mode\":\"default\",\"reducedSteps\":true,\"fontScale\":1.12}");
    assertThatThrownBy(() -> UserPreferenceSchema.requireValidPreference("ui.display", badScale))
        .isInstanceOf(BusinessException.class);
    JsonNode missing = tree("{\"mode\":\"default\"}");
    assertThatThrownBy(() -> UserPreferenceSchema.requireValidPreference("ui.display", missing))
        .isInstanceOf(BusinessException.class);
    JsonNode pageSize = tree("{\"pageSize\":30}");
    assertThatThrownBy(
            () -> UserPreferenceSchema.requireValidPreference("ui.table.SCR-0201", pageSize))
        .isInstanceOf(BusinessException.class);
    JsonNode tooManyPins =
        tree("{\"pinnedWidgets\":[\"a1\",\"a2\",\"a3\",\"a4\",\"a5\",\"a6\",\"a7\"]}");
    assertThatThrownBy(
            () -> UserPreferenceSchema.requireValidPreference("ui.home.layout", tooManyPins))
        .isInstanceOf(BusinessException.class);
    JsonNode freeText = tree("{\"freeText\":\"Nguyễn Văn A\"}");
    assertThatThrownBy(() -> UserPreferenceSchema.requireValidCriteria(freeText))
        .isInstanceOf(BusinessException.class);
    JsonNode badDate = tree("{\"dateRanges\":{\"dueDate\":{\"from\":\"01/10/2026\"}}}");
    assertThatThrownBy(() -> UserPreferenceSchema.requireValidCriteria(badDate))
        .isInstanceOf(BusinessException.class);
  }
}
