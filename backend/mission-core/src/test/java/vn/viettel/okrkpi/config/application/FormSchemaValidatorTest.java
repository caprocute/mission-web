package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/** CORE-08-13: kiểm biểu mẫu khi lưu. */
class FormSchemaValidatorTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final FormSchemaValidator validator = new FormSchemaValidator();

  private static JsonNode json(String s) throws Exception {
    return JSON.readTree(s);
  }

  static final String VALID =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "required":["ty_le_hsba_dat"],
       "properties":{
         "ty_le_hsba_dat":{"type":"number","x-cel":"self >= 0.0 && self <= 100.0","x-classification":"C1"},
         "su_co_y_khoa":{"type":"string","enum":["CO","KHONG"],"x-classification":"C1"},
         "mo_ta_su_co":{"type":"string","maxLength":2000,"x-classification":"C2"}}}
      """;

  @Test
  void validFormPassesAndListsFields() throws Exception {
    FormSchemaValidator.Result r =
        validator.validate(
            json(VALID),
            json("{\"mo_ta_su_co\":{\"hideExpression\":\"form.su_co_y_khoa != 'CO'\"}}"),
            json(
                "[{\"field\":\"ty_le_hsba_dat\",\"expr\":\"form.ty_le_hsba_dat > 50.0\",\"message\":\"x\"}]"));
    assertThat(r.passed()).as(r.toString()).isTrue();
    assertThat(r.fields())
        .containsEntry("ty_le_hsba_dat", "number")
        .containsEntry("mo_ta_su_co", "string");
  }

  @Test
  void structuralErrorsAreReported() throws Exception {
    JsonNode bad =
        json(
            """
            {"type":"object","required":["thieu"],
             "properties":{
               "Sai-Khoa":{"type":"string","x-classification":"C1"},
               "nhom":{"type":"object","properties":{
                  "trung":{"type":"string","x-classification":"C1"}}},
               "trung":{"type":"string","x-classification":"C1"},
               "mat":{"type":"string","x-classification":"C3"},
               "kieu":{"type":"text","x-classification":"C1"}}}
            """);
    FormSchemaValidator.Result r = validator.validate(bad, json("{}"), null);
    assertThat(r.structure())
        .extracting(ProblemFieldError::field)
        .contains(
            "schemaJson.required",
            "schemaJson.properties.Sai-Khoa",
            "schemaJson.properties.trung",
            "schemaJson.properties.mat.x-classification",
            "schemaJson.properties.kieu.type");
  }

  @Test
  void celErrorsAndCyclesAreReported() throws Exception {
    JsonNode schema =
        json(
            """
            {"type":"object","properties":{
              "a":{"type":"number","x-cel":"self >= ","x-classification":"C1"},
              "b":{"type":"string","x-classification":"C1"}}}
            """);
    JsonNode ui =
        json(
            """
            {"a":{"hideExpression":"form.b == 'x'"},"b":{"hideExpression":"form.a > 1.0"}}
            """);
    FormSchemaValidator.Result r =
        validator.validate(
            schema, ui, json("[{\"field\":\"khong_co\",\"expr\":\"1 +\",\"message\":\"m\"}]"));
    assertThat(r.cel())
        .extracting(ProblemFieldError::field)
        .containsExactlyInAnyOrder("schemaJson.properties.a.x-cel", "celRules[0].expr");
    assertThat(r.cel().get(0).message()).startsWith("Biểu thức CEL không hợp lệ: ");
    assertThat(r.structure())
        .extracting(ProblemFieldError::field)
        .contains("celRules[0].field", "uiJson.a.hideExpression");
  }
}
