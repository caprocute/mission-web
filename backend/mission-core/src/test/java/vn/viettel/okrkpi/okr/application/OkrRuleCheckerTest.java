package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.KrInput;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.ObjectiveInput;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Policy;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Rule;

/**
 * CORE-01-04: bộ ca dùng chung {@code data/golden/okr-rules/okr-rules.json} chạy trên cel-java cho
 * cùng kết quả mà trình duyệt chạy bằng cel-js; bộ quy tắc trong tệp phải trùng bộ mặc định của máy
 * chủ; một mục tiêu năm KR kiểm trong ≤ 50 ms p95.
 */
class OkrRuleCheckerTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final OkrRuleChecker checker = new OkrRuleChecker();

  static JsonNode suite() throws IOException {
    for (String dir : List.of("data", "../data", "../../data", "../../../data")) {
      Path p = Path.of(dir, "golden", "okr-rules", "okr-rules.json");
      if (Files.exists(p)) {
        return JSON.readTree(p.toFile());
      }
    }
    throw new IllegalStateException("Không thấy data/golden/okr-rules/okr-rules.json");
  }

  static Stream<Arguments> cases() throws IOException {
    List<Arguments> list = new ArrayList<>();
    suite().get("cases").forEach(c -> list.add(Arguments.of(c.get("id").asText(), c)));
    return list.stream();
  }

  @Test
  void boQuyTacTrongTepTrungBoMacDinhCuaMayChu() throws IOException {
    JsonNode suite = suite();
    List<Rule> fileRules = new ArrayList<>();
    suite.get("rules").forEach(r -> fileRules.add(rule(r)));
    assertThat(fileRules).containsExactlyElementsOf(OkrRuleChecker.DEFAULT_RULES);
    assertThat(strings(suite.at("/policy/vagueWords")))
        .containsExactlyElementsOf(OkrRuleChecker.DEFAULT_VAGUE_WORDS);
    assertThat(strings(suite.at("/policy/taskVerbs")))
        .containsExactlyElementsOf(OkrRuleChecker.DEFAULT_TASK_VERBS);
    assertThat(suite.at("/policy/recommendedMax").asInt())
        .isEqualTo(OkrRuleChecker.DEFAULT_POLICY.recommendedMax());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void caDungChung(String id, JsonNode c) {
    Policy policy = OkrRuleChecker.DEFAULT_POLICY;
    String scope = c.get("scope").asText();
    Map<String, Object> vars;
    if ("objective".equals(scope)) {
      vars = new LinkedHashMap<>();
      c.get("vars")
          .fields()
          .forEachRemaining(
              e -> {
                JsonNode v = e.getValue();
                vars.put(
                    e.getKey(),
                    v.isBoolean()
                        ? v.asBoolean()
                        : v.isIntegralNumber()
                            ? v.asLong()
                            : v.isNumber() ? v.asDouble() : v.asText());
              });
    } else {
      JsonNode in = c.get("input");
      vars = OkrRuleChecker.krVars(kr(in), uuid(in, "ownerPersonId"), policy);
      c.get("expectedVars")
          .fields()
          .forEachRemaining(e -> assertThat(vars.get(e.getKey())).isEqualTo(e.getValue().asText()));
    }
    List<String> expected = strings(c.get("violations"));
    assertThat(checker.violations(scope, vars, policy.rules()))
        .as(c.get("description").asText())
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void mucTieuDayDuCoNhanDatVaKhongCoLoiChan() {
    List<Finding> findings = checker.check(objective(List.of(kr("0.5"), kr("0.5"))), defaults());
    assertThat(OkrRuleChecker.blocking(findings)).isFalse();
    assertThat(findings)
        .extracting(Finding::message)
        .contains("Có căn cứ", "Tổng trọng số KR = 100%");
  }

  @Test
  void trongSoChinMuoiChinPhayChinBiChanVaThongBaoDungSo() {
    List<Finding> findings = checker.check(objective(List.of(kr("0.5"), kr("0.499"))), defaults());
    assertThat(OkrRuleChecker.blocking(findings)).isTrue();
    assertThat(findings)
        .filteredOn(f -> "BR-02-07".equals(f.ruleId()))
        .extracting(Finding::message)
        .containsExactly("Tổng trọng số KR là 99.9%, cần bằng 100%.");
  }

  @Test
  void quyTacHongCuaPolicyPackBiBoQua() {
    Rule broken =
        new Rule("X", "BR-02-03", "objective", "CHAN", "true", "o.khongCo +", "Hỏng", null, false);
    Policy policy = new Policy("thu", 3, List.of(), List.of(), List.of(), List.of(broken));
    assertThat(checker.check(objective(List.of(kr("1"))), policy)).isEmpty();
  }

  @Test
  void namKrKiemDuoiNamMuoiMiliGiayP95() {
    ObjectiveInput input =
        objective(List.of(kr("0.2"), kr("0.2"), kr("0.2"), kr("0.2"), kr("0.2")));
    Policy policy = defaults();
    for (int i = 0; i < 50; i++) {
      checker.check(input, policy);
    }
    long[] samples = new long[200];
    for (int i = 0; i < samples.length; i++) {
      long t = System.nanoTime();
      checker.check(input, policy);
      samples[i] = System.nanoTime() - t;
    }
    Arrays.sort(samples);
    assertThat(samples[(int) (samples.length * 0.95)] / 1_000_000.0).isLessThan(50.0);
  }

  // ---------------------------------------------------------------- tiện ích

  private static Policy defaults() {
    return OkrRuleChecker.DEFAULT_POLICY;
  }

  private static ObjectiveInput objective(List<KrInput> krs) {
    return new ObjectiveInput(
        "Nâng chất lượng sinh hoạt chi bộ",
        1,
        true,
        false,
        true,
        "O2",
        2,
        null,
        UUID.randomUUID(),
        krs);
  }

  private static KrInput kr(String weight) {
    return new KrInput(
        UUID.randomUUID(),
        "KR 1.1",
        "Kiểm tra 100% chi bộ trực thuộc",
        "%",
        BigDecimal.ZERO,
        BigDecimal.valueOf(100),
        new BigDecimal(weight),
        LocalDate.of(2027, 3, 31),
        UUID.randomUUID());
  }

  private static KrInput kr(JsonNode in) {
    return new KrInput(
        null,
        in.get("code").asText(),
        in.get("title").asText(),
        in.get("unit").asText(),
        decimal(in, "baselineValue"),
        decimal(in, "targetValue"),
        BigDecimal.ONE,
        in.get("dueDate").isNull() ? null : LocalDate.parse(in.get("dueDate").asText()),
        uuid(in, "verifierPersonId"));
  }

  private static BigDecimal decimal(JsonNode in, String field) {
    JsonNode v = in.get(field);
    return v == null || v.isNull() ? null : v.decimalValue();
  }

  private static UUID uuid(JsonNode in, String field) {
    JsonNode v = in.get(field);
    return v == null || v.isNull() ? null : UUID.fromString(v.asText());
  }

  private static Rule rule(JsonNode r) {
    return new Rule(
        r.get("id").asText(),
        r.get("br").asText(),
        r.get("scope").asText(),
        r.get("severity").asText(),
        r.get("when").asText(),
        r.get("assert").asText(),
        r.get("message").asText(),
        r.get("passLabel").isNull() ? null : r.get("passLabel").asText(),
        r.get("serverOnly").asBoolean());
  }

  private static List<String> strings(JsonNode array) {
    List<String> list = new ArrayList<>();
    array.forEach(v -> list.add(v.asText()));
    return list;
  }
}
