package vn.viettel.okrkpi.scoring.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.DelaySegment;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;

/**
 * Chuỗi tính của DV-07-03 trên gói mẫu {@code khoi-dang/quy-dinh-moi} với bảng DMN chạy thật: ca
 * vàng của bộ ca mẫu (DL-03), kiểm thử thuộc tính 10.000 bộ, tính tất định và vết tính.
 */
class ScoreCalculatorTest {

  private static final UUID PERSON = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5a01");
  private static final UUID PROFILE = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5b01");

  private static FlowableDecisionTables tables;
  private static CompiledPolicy policy;
  private final ScoreCalculator calculator = new ScoreCalculator();

  @BeforeAll
  static void compile() {
    tables = new FlowableDecisionTables();
    policy =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables)
            .compile(SamplePolicyPack.content());
  }

  @AfterAll
  static void close() {
    tables.destroy();
  }

  @TestFactory
  @SuppressWarnings("unchecked")
  Stream<DynamicTest> caVangCuaBoCaMau() throws IOException {
    Map<String, Object> suite;
    try (InputStream in =
        Files.newInputStream(SamplePolicyPack.DIR.resolve("tests/bo-ca-mau-gia-lap.yaml"))) {
      suite = new Yaml(new LoaderOptions()).load(in);
    }
    Map<String, Object> defaults = (Map<String, Object>) suite.get("mac_dinh");
    List<DynamicTest> tests = new ArrayList<>();
    for (Object o : (List<?>) suite.get("ca")) {
      Map<String, Object> c = (Map<String, Object>) o;
      Map<String, Object> in = (Map<String, Object>) c.get("dau_vao");
      if (!"QUY".equals(c.get("loai")) || in == null || !(in.get("nhiem_vu") instanceof List)) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(c.get("id") + ": " + c.get("mo_ta"), () -> golden(c, defaults)));
    }
    assertThat(tests).hasSizeGreaterThanOrEqualTo(30);
    return tests.stream();
  }

  @SuppressWarnings("unchecked")
  private void golden(Map<String, Object> c, Map<String, Object> defaults) {
    Map<String, Object> in = (Map<String, Object>) c.get("dau_vao");
    Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) defaults.get("QUY"));
    in.forEach((k, v) -> vars.put(k, v));
    List<Map<String, Object>> tasks = new ArrayList<>();
    for (Object t : (List<?>) in.get("nhiem_vu")) {
      Map<String, Object> nv = new LinkedHashMap<>((Map<String, Object>) defaults.get("nhiem_vu"));
      nv.putAll((Map<String, Object>) t);
      tasks.add(nv);
    }
    vars.put("nhiem_vu", tasks);
    Map<String, Object> overrides =
        c.get("tham_so") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    ScoreCalculator.Calculation r =
        calculator.calculate(policy, CanonicalDoc.of(vars), vars, overrides);
    Map<String, Object> expected = (Map<String, Object>) c.get("mong_doi");
    for (String key : List.of("diem", "ty_le_vuot")) {
      if (expected.get(key) instanceof Number n) {
        assertThat(((Number) r.outputs().get(key)).doubleValue())
            .as("%s %s", c.get("id"), key)
            .isCloseTo(n.doubleValue(), within(1e-9));
      }
    }
    if (expected.get("muc_tieu_chi_chung") != null) {
      assertThat(r.outputs().get("muc_tieu_chi_chung"))
          .as("%s muc_tieu_chi_chung", c.get("id"))
          .isEqualTo(expected.get("muc_tieu_chi_chung"));
    }
    if (expected.get("kpi_nhiem_vu") instanceof List<?> kpis) {
      assertThat(r.tasks()).hasSameSizeAs(kpis);
      for (int i = 0; i < kpis.size(); i++) {
        double want = ((Number) kpis.get(i)).doubleValue();
        int digits = BigDecimal.valueOf(want).stripTrailingZeros().scale();
        double got = ((Number) r.tasks().get(i).get("kpi_nhiem_vu")).doubleValue();
        assertThat(
                BigDecimal.valueOf(got)
                    .setScale(Math.max(digits, 0), java.math.RoundingMode.HALF_UP)
                    .doubleValue())
            .as("%s kpi_nhiem_vu[%d]", c.get("id"), i)
            .isEqualTo(want);
      }
    }
  }

  @Test
  void thuocTinhMuoiNghinBoDuLieu() {
    Random random = new Random(20261022L);
    for (int i = 0; i < 10_000; i++) {
      ScoringInput input = randomInput(random);
      ScoreCalculator.Calculation r = calculator.calculate(policy, input);
      assertThat(r.totalScore()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
      assertThat(r.totalScore().scale()).isLessThanOrEqualTo(1);
      for (Map<String, Object> t : r.tasks()) {
        double a = ((Number) t.get("kpi_A")).doubleValue();
        double b = ((Number) t.get("kpi_B")).doubleValue();
        double cc = ((Number) t.get("kpi_C")).doubleValue();
        assertThat(a).isBetween(0.0, 1.0);
        assertThat(b).isBetween(0.0, 1.0);
        assertThat(cc).isBetween(0.0, 1.0);
      }
      assertThat(((Number) r.outputs().get("ty_le_vuot")).doubleValue()).isBetween(0.0, 1.0);
    }
  }

  @Test
  void cungDauVaoCungBamKetQua() {
    ScoringInput input = randomInput(new Random(7));
    ScoreCalculator.Calculation first = calculator.calculate(policy, input);
    ScoreCalculator.Calculation second = calculator.calculate(policy, input);
    assertThat(second.result().hashHex()).isEqualTo(first.result().hashHex());
    assertThat(second.input().hashHex()).isEqualTo(first.input().hashHex());
    assertThat(second.trace()).isEqualTo(first.trace());
    assertThat(first.result().json()).contains("\"policy_version\":\"2.1.0\"");
  }

  @Test
  void vetTinhGhiTungBuocKemCanCu() {
    UUID taskId = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5c01");
    TaskInput key =
        new TaskInput(
            taskId,
            "NV-2026-Q4-0142",
            "THEN_CHOT",
            1,
            new BigDecimal("30"),
            false,
            List.of(product("0.75", 1, false)),
            List.of(
                new DelaySegment(2, 4, DelaySegment.Cause.CHU_QUAN),
                new DelaySegment(3, 3, DelaySegment.Cause.KHACH_QUAN)));
    TaskInput regular =
        new TaskInput(
            UUID.randomUUID(),
            "NV-2026-Q4-0001",
            "THUONG_XUYEN",
            2,
            null,
            false,
            List.of(product("1", 0, false), product("1", 0, true)),
            List.of());
    ScoringInput input =
        new ScoringInput(
            PROFILE,
            PERSON,
            "DANG",
            List.of(key, regular),
            Map.of("ty_le_dap_ung_tieu_chi_chung", 0.95, "ty_le_O", 1.0));
    ScoreCalculator.Calculation r = calculator.calculate(policy, input);

    // Ví dụ SCR-0703: A = 1, B = 0,75, C = 1 − 0,05 × 2 = 0,9, KPI ≈ 0,883.
    Map<String, Object> t = r.tasks().get(0);
    assertThat(((Number) t.get("kpi_A")).doubleValue()).isEqualTo(1.0);
    assertThat(((Number) t.get("kpi_B")).doubleValue()).isEqualTo(0.75);
    assertThat(((Number) t.get("kpi_C")).doubleValue()).isCloseTo(0.9, within(1e-9));
    assertThat(((Number) t.get("kpi_nhiem_vu")).doubleValue()).isCloseTo(0.8833, within(1e-4));
    // Sản phẩm bị chặn trùng không được đếm: A = 1/2.
    assertThat(((Number) r.tasks().get(1).get("kpi_A")).doubleValue()).isEqualTo(0.5);
    assertThat(r.outputs()).containsEntry("muc_tieu_chi_chung", "DAM_BAO");

    List<TraceStep> trace = r.trace();
    assertThat(trace).hasSizeBetween(20, 60);
    TraceStep a = trace.get(0);
    assertThat(a.stepNo()).isEqualTo(1);
    assertThat(a.ruleCode()).isEqualTo("kpi_A");
    assertThat(a.legalRef()).contains("BR-09-01");
    assertThat(a.inputs().json()).contains("\"task_id\":\"" + taskId + "\"");
    assertThat(a.output().json()).isEqualTo("{\"kpi_A\":1}");
    TraceStep b = trace.get(1);
    assertThat(b.inputs().json()).contains("\"tham_so.cach_tinh_chat_luong\":\"NAM_MUC_PL3\"");
    assertThat(b.inputs().json()).doesNotContain("\"tham_so\":");
    assertThat(trace).anyMatch(s -> s.ruleCode().equals("tieu_chi_chung"));
    assertThat(trace).anyMatch(s -> s.ruleCode().equals("diem"));
    assertThat(trace).noneMatch(s -> s.ruleCode().equals("muc_cuoi"));
    assertThat(trace).noneMatch(s -> s.ruleCode().equals("xep_loai"));
    TraceStep total =
        trace.stream().filter(s -> s.ruleCode().equals("diem_ket_qua")).findFirst().orElseThrow();
    assertThat(total.inputs().json()).contains("\"diem_nhom\"").doesNotContain("\"ds_kpi\"");
  }

  @Test
  void thieuPhamViNhiemVuThiBaoLoi() {
    CompiledPolicy empty =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables)
            .compile(
                new vn.viettel.okrkpi.scoring.domain.PolicyContent(
                    policy.pinned(), Map.of("definitions", Map.of()), List.of(), List.of()));
    ScoringInput input = new ScoringInput(PROFILE, PERSON, "DANG", List.of(), Map.of());
    assertThatThrownBy(() -> calculator.calculate(empty, input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nhiem_vu");
  }

  private static ProductInput product(String quality, int revisions, boolean blocked) {
    return new ProductInput(
        UUID.randomUUID(), ProductInput.CONFIRMED, new BigDecimal(quality), blocked, revisions);
  }

  private static final String[] LEVELS = {"1", "0.75", "0.5", "0.25", "0"};

  static ScoringInput randomInput(Random random) {
    int n = random.nextInt(12);
    List<TaskInput> tasks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int planned = random.nextInt(15);
      int products = random.nextInt(planned + 6);
      List<ProductInput> list = new ArrayList<>();
      for (int p = 0; p < products; p++) {
        String status = random.nextInt(5) == 0 ? "YEU_CAU_BO_SUNG" : ProductInput.CONFIRMED;
        list.add(
            new ProductInput(
                new UUID(i, p),
                status,
                new BigDecimal(LEVELS[random.nextInt(LEVELS.length)]),
                random.nextInt(10) == 0,
                random.nextInt(4)));
      }
      List<DelaySegment> delays = new ArrayList<>();
      for (int d = random.nextInt(3); d > 0; d--) {
        int w = random.nextInt(40);
        delays.add(
            new DelaySegment(
                w,
                w + random.nextInt(10),
                random.nextBoolean()
                    ? DelaySegment.Cause.CHU_QUAN
                    : DelaySegment.Cause.KHACH_QUAN));
      }
      tasks.add(
          new TaskInput(
              new UUID(42, i),
              "NV-" + i,
              random.nextBoolean() ? "THEN_CHOT" : "THUONG_XUYEN",
              planned,
              BigDecimal.valueOf(random.nextInt(101)),
              random.nextInt(8) == 0,
              list,
              delays));
    }
    return new ScoringInput(
        PROFILE,
        PERSON,
        random.nextBoolean() ? "DANG" : "NHA_NUOC",
        tasks,
        Map.of(
            "ty_le_dap_ung_tieu_chi_chung", random.nextInt(101) / 100.0,
            "ty_le_O", random.nextInt(101) / 100.0,
            "loai_tru", random.nextInt(20) == 0));
  }
}
