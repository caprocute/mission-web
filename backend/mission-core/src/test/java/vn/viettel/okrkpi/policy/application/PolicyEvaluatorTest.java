package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.ResultComparison;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Bộ đánh giá của CORE-05-07 trên hai gói giả lập: toàn bộ bộ ca vàng của DL-03 (gồm ca đặc thù)
 * đạt trên cel-java với bảng quyết định đọc từ DMN của gói; quy tắc ghi đè định nghĩa; biểu thức
 * sai kiểu bị từ chối {@code POLICY-422-CEL-TYPE}; vết CEL đủ để chạy lại trên cel-js.
 */
class PolicyEvaluatorTest {

  static final Path DATA = Path.of("../../data/policy-pack");
  static final ObjectMapper JSON = new ObjectMapper();

  static final CelEngine CEL =
      new CelEngine(
          StaticWorkingCalendar.weekendsOnly(),
          new CelSandboxOptions(
              CelSandboxOptions.DEFAULT_MAX_EXPRESSION_LENGTH,
              CelSandboxOptions.DEFAULT_MAX_AST_DEPTH,
              CelSandboxOptions.DEFAULT_MAX_AST_NODE_COUNT,
              CelSandboxOptions.DEFAULT_MAX_COMPREHENSION_ITERATIONS,
              java.time.Duration.ofSeconds(5),
              CelSandboxOptions.DEFAULT_PROGRAM_CACHE_MAX_SIZE));

  static Stream<Arguments> packs() {
    return Stream.of(
        Arguments.of("khoi-dang/quy-dinh-moi"), Arguments.of("khoi-chinh-quyen/nd335"));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> manifest(String pack) throws IOException {
    return new Yaml().load(Files.readString(DATA.resolve(pack).resolve("policy.yaml")));
  }

  static PolicyEvaluator evaluator(String pack, List<PolicyRule> rules) throws IOException {
    Map<String, Object> m = manifest(pack);
    return new PolicyEvaluator(CEL, JSON, m, rules, DmnSpecs.of(DATA.resolve(pack), m));
  }

  static GoldenSuiteYaml.Suite suite(String pack) throws IOException {
    return GoldenSuiteYaml.parse(
        Files.readString(DATA.resolve(pack).resolve("tests/bo-ca-mau-gia-lap.yaml")));
  }

  @ParameterizedTest
  @MethodSource("packs")
  void wholeGoldenSuitePassesOnCelJavaAndDecisionTables(String pack) throws IOException {
    PolicyEvaluator ev = evaluator(pack, List.of());
    SoftAssertions soft = new SoftAssertions();
    int ran = 0;
    for (GoldenSuiteYaml.Entry e : suite(pack).entries()) {
      Map<String, Object> input = JSON.convertValue(e.input(), Map.class);
      Map<String, Object> out = ev.evaluate(input, false).outputs();
      soft.assertThat(ResultComparison.diff(e.expected(), out)).as(e.caseCode()).isEmpty();
      ran++;
    }
    soft.assertAll();
    assertThat(ran).isGreaterThanOrEqualTo(7);
  }

  @Test
  void tracesRecordEveryCelCallWithReferencedInputs() throws IOException {
    PolicyEvaluator ev = evaluator("khoi-dang/quy-dinh-moi", List.of());
    GoldenSuiteYaml.Entry q1 = suite("khoi-dang/quy-dinh-moi").entries().get(0);
    PolicyEvaluator.Outcome out = ev.evaluate(q1.input(), true);
    assertThat(out.traces()).isNotEmpty();
    PolicyEvaluator.Trace first = out.traces().get(0);
    assertThat(first.label()).isEqualTo("nhiem_vu[0].kpi_A");
    assertThat(first.evaluation().inputs()).containsOnlyKeys("nv");
    assertThat(first.evaluation().returnType()).isEqualTo("DECIMAL");
    assertThat(out.traces()).extracting(PolicyEvaluator.Trace::label).contains("diem", "muc_cuoi");
    assertThat(out.outputs()).containsEntry("xep_loai", "HTXSNV").containsKey("kpi_nhiem_vu");
  }

  @Test
  void ruleOfVersionOverridesManifestDefinition() throws IOException {
    UUID v = UUID.randomUUID();
    PolicyRule always60 =
        new PolicyRule(UUID.randomUUID(), v, "diem", "60.0", "DECIMAL", "{}", "", 100, "{}", true);
    PolicyRule extra =
        new PolicyRule(
            UUID.randomUUID(), v, "diem_goc", "diem_ket_qua", "DECIMAL", "{}", "", 900, "{}", true);
    PolicyEvaluator ev = evaluator("khoi-dang/quy-dinh-moi", List.of(always60, extra));
    Map<String, Object> out =
        ev.evaluate(suite("khoi-dang/quy-dinh-moi").entries().get(0).input(), false).outputs();
    assertThat(out).containsEntry("diem", 60.0).containsEntry("diem_goc", 100.0);
    assertThat(out.get("xep_loai")).isNotEqualTo("HTXSNV");
  }

  @Test
  void invalidExpressionIsRejectedBeforeRunning() {
    UUID v = UUID.randomUUID();
    PolicyRule bad =
        new PolicyRule(
            UUID.randomUUID(),
            v,
            "kpi_A",
            "nv.ke_hoach + 'a'",
            "DECIMAL",
            "{}",
            "",
            10,
            "{}",
            true);
    assertThatThrownBy(() -> evaluator("khoi-dang/quy-dinh-moi", List.of(bad)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-422-CEL-TYPE");
  }

  @Test
  void specialCaseAndFlatInputs() throws IOException {
    PolicyEvaluator ev = evaluator("khoi-dang/quy-dinh-moi", List.of());
    assertThat(ev.evaluate(Map.of("dau_vao", Map.of("truong_hop", "KIEM_NHIEM")), false).outputs())
        .containsEntry("hieu_ung", "XEP_LOAI_BINH_THUONG");
    assertThatThrownBy(() -> ev.evaluate(Map.of("truong_hop", "KHONG_CO"), false))
        .isInstanceOf(IllegalArgumentException.class);
    Map<String, Object> nv = new HashMap<>();
    nv.put("ke_hoach", 4);
    nv.put("so_luong_duyet", 4);
    nv.put("muc_chat_luong", 1.0);
    nv.put("so_lan_chinh_sua", 0);
    nv.put("ngay_lam_viec_cham_chu_quan", 2);
    nv.put("ngay_lich_cham_chu_quan", 3);
    Map<String, Object> out = ev.evaluate(Map.of("nv", nv), false).outputs();
    assertThat((Double) out.get("kpi_C"))
        .isEqualTo(0.9, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(out).doesNotContainKey("diem");
  }

  @Test
  void paramsNestOverridesByDottedKey() throws IOException {
    PolicyEvaluator ev = evaluator("khoi-dang/quy-dinh-moi", List.of());
    Map<String, Object> ts = ev.params(Map.of("htxs.nguong_ty_le_vuot", 0.5));
    assertThat(((Map<?, ?>) ts.get("htxs")).get("nguong_ty_le_vuot")).isEqualTo(0.5);
    assertThat(ts).containsKey("nhom");
    assertThat(ev.roundScore(59.995)).isEqualTo(60.0);
    assertThat(ev.levelKey()).isEqualTo("muc_cuoi");
    assertThat(ev.quarterly()).isTrue();
  }

  @Test
  void decisionTableWithoutMatchLeavesLevelEmpty() throws IOException {
    Map<String, Object> m = new LinkedHashMap<>(manifest("khoi-dang/quy-dinh-moi"));
    DecisionTableSpec onlyTop =
        new DecisionTableSpec(
            "FIRST",
            List.of(new DecisionTableSpec.Column("diem", "Điểm", "number")),
            List.of(new DecisionTableSpec.Row(Map.of("diem", ">= 99"), "HTXSNV", "", "")));
    PolicyEvaluator ev = new PolicyEvaluator(CEL, JSON, m, List.of(), Map.of("xep_loai", onlyTop));
    List<Map<String, Object>> tasks = new ArrayList<>();
    tasks.add(
        Map.of(
            "nhom",
            "THEN_CHOT",
            "ke_hoach",
            10,
            "so_luong_duyet",
            5,
            "muc_chat_luong",
            1.0,
            "so_lan_chinh_sua",
            0,
            "ngay_lam_viec_cham_chu_quan",
            0,
            "ngay_lich_cham_chu_quan",
            0,
            "diem_chuan",
            10,
            "khong_hoan_thanh_chu_quan",
            false));
    Map<String, Object> input =
        new HashMap<>(suite("khoi-dang/quy-dinh-moi").entries().get(0).input());
    @SuppressWarnings("unchecked")
    Map<String, Object> dv = new HashMap<>((Map<String, Object>) input.get("dau_vao"));
    dv.put("nhiem_vu", tasks);
    input.put("dau_vao", dv);
    Map<String, Object> out = ev.evaluate(input, false).outputs();
    assertThat(out).containsKey("diem").doesNotContainKeys("xep_loai", "muc_cuoi");
  }
}
