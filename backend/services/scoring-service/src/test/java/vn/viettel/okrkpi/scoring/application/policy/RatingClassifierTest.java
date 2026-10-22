package vn.viettel.okrkpi.scoring.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import vn.viettel.okrkpi.scoring.domain.AnnualInput;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.QuarterResult;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;

/**
 * Ca vàng xếp loại (DV-07-05) của hai gói mẫu: dòng bảng, mức của bảng, mức cuối, hạ mức, điều kiện
 * thiếu theo bộ ca của gói; câu giải thích khớp nguyên văn {@code
 * data/golden/xep-loai/giai-thich-mong-doi.yaml}. Câu thực tế được ghi ra {@code
 * target/giai-thich-thuc-te.yaml} để đối chiếu khi gói đổi.
 */
class RatingClassifierTest {

  private static final Path EXPECTED =
      Path.of("../../../data/golden/xep-loai/giai-thich-mong-doi.yaml");
  private static final Path ACTUAL = Path.of("target/giai-thich-thuc-te.yaml");
  private static final UUID PERSON = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5a01");
  private static final UUID PROFILE = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5b01");

  /** Thuộc tính hồ sơ luồng Đảng đủ mọi điều kiện ngoài tỷ lệ vượt. */
  private static final Map<String, Object> PARTY =
      Map.of(
          "ty_le_O", 1.0,
          "ty_le_dap_ung_tieu_chi_chung", 1.0,
          "giai_phap_dot_pha_da_kiem_chung", true,
          "loai_tru", false,
          "la_nguoi_dung_dau", false,
          "tap_the_dat_05_chi_tieu", true,
          "tap_the_dat_tat_ca_O", true,
          "ty_le_don_vi_khtnv", 0.0,
          "giu_chuc_vu", false,
          "xep_loai_nha_nuoc", "");

  private static FlowableDecisionTables tables;
  private static CompiledPolicy dang;
  private static CompiledPolicy nhaNuoc;
  private static Map<String, Map<String, String>> expected;
  private static final Map<String, Map<String, String>> ACTUALS = new TreeMap<>();

  private final ScoreCalculator calculator = new ScoreCalculator();
  private final RatingClassifier classifier = new RatingClassifier();

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void compile() throws IOException {
    tables = new FlowableDecisionTables();
    PolicyCompiler compiler =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables);
    dang = compiler.compile(SamplePolicyPack.content());
    nhaNuoc = compiler.compile(SamplePolicyPack.nd335());
    try (InputStream in = Files.newInputStream(EXPECTED)) {
      expected = new Yaml(new LoaderOptions()).load(in);
    }
  }

  @AfterAll
  static void close() throws IOException {
    tables.destroy();
    StringBuilder out = new StringBuilder();
    ACTUALS.forEach(
        (pack, cases) -> {
          out.append(pack).append(":\n");
          cases.forEach(
              (id, text) ->
                  out.append("  ")
                      .append(id)
                      .append(": \"")
                      .append(text.replace("\"", "\\\""))
                      .append("\"\n"));
        });
    Files.createDirectories(ACTUAL.getParent());
    Files.writeString(ACTUAL, out, StandardCharsets.UTF_8);
  }

  @TestFactory
  Stream<DynamicTest> caVangXepLoaiLuongDang() throws IOException {
    return cases(dang, SamplePolicyPack.DIR, "QUY", 30);
  }

  @TestFactory
  Stream<DynamicTest> caVangXepLoaiLuongNhaNuoc() throws IOException {
    return cases(nhaNuoc, SamplePolicyPack.ND335, "NHA_NUOC", 7);
  }

  @TestFactory
  @SuppressWarnings("unchecked")
  Stream<DynamicTest> caVangXepLoaiNam() throws IOException {
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
      if (!"NAM".equals(c.get("loai")) || in == null || !(in.get("ket_qua_quy") instanceof List)) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(c.get("id") + ": " + c.get("mo_ta"), () -> annual(c, defaults)));
    }
    assertThat(tests).hasSizeGreaterThanOrEqualTo(15);
    return tests.stream();
  }

  @SuppressWarnings("unchecked")
  private void annual(Map<String, Object> c, Map<String, Object> defaults) {
    Map<String, Object> in = (Map<String, Object>) c.get("dau_vao");
    Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) defaults.get("NAM"));
    vars.putAll(in);
    List<Map<String, Object>> quarters = new ArrayList<>();
    for (Object q : (List<?>) in.get("ket_qua_quy")) {
      Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) q);
      m.putIfAbsent("co_xep_loai", !"".equals(m.get("xep_loai")));
      quarters.add(m);
    }
    vars.put("ket_qua_quy", quarters);
    Map<String, Object> overrides =
        c.get("tham_so") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    ScoreCalculator.Calculation calc =
        calculator.calculateAnnual(dang, CanonicalDoc.of(vars), vars, overrides);
    RatingClassifier.Rating r = classifier.classify(dang, calc);
    String id = String.valueOf(c.get("id"));
    synchronized (ACTUALS) {
      ACTUALS
          .computeIfAbsent(dang.pinned().packCode(), k -> new TreeMap<>())
          .put(id, r.explanation());
    }
    Map<String, Object> want = (Map<String, Object>) c.get("mong_doi");
    for (String key : List.of("diem_nam", "so_quy_khtnv")) {
      if (want.get(key) instanceof Number n) {
        assertThat(((Number) calc.outputs().get(key)).doubleValue())
            .as("%s %s", id, key)
            .isEqualTo(n.doubleValue());
      }
    }
    if (want.get("co_bo_tri_lai") instanceof Boolean flag) {
      assertThat(calc.outputs().get("co_bo_tri_lai")).as("%s bố trí lại", id).isEqualTo(flag);
    }
    if (want.get("dong_dmn") != null) {
      assertThat(r.ruleId()).as("%s dòng", id).isEqualTo(want.get("dong_dmn"));
    }
    assertThat(r.tableLevel()).as("%s mức năm", id).isEqualTo(want.get("xep_loai_nam"));
    assertThat(r.level()).as("%s mức cuối năm", id).isEqualTo(want.get("xep_loai_nam"));
    assertThat(r.explanation())
        .as("%s câu giải thích", id)
        .isEqualTo(expected.getOrDefault(dang.pinned().packCode(), Map.of()).get(id));
  }

  @SuppressWarnings("unchecked")
  private Stream<DynamicTest> cases(CompiledPolicy policy, Path dir, String kind, int min)
      throws IOException {
    Map<String, Object> suite;
    try (InputStream in = Files.newInputStream(dir.resolve("tests/bo-ca-mau-gia-lap.yaml"))) {
      suite = new Yaml(new LoaderOptions()).load(in);
    }
    Map<String, Object> defaults = (Map<String, Object>) suite.get("mac_dinh");
    List<DynamicTest> tests = new ArrayList<>();
    for (Object o : (List<?>) suite.get("ca")) {
      Map<String, Object> c = (Map<String, Object>) o;
      Map<String, Object> in = (Map<String, Object>) c.get("dau_vao");
      if (!kind.equals(c.get("loai")) || in == null || !(in.get("nhiem_vu") instanceof List)) {
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              c.get("id") + ": " + c.get("mo_ta"), () -> golden(policy, c, defaults, kind)));
    }
    assertThat(tests).hasSizeGreaterThanOrEqualTo(min);
    return tests.stream();
  }

  @SuppressWarnings("unchecked")
  private void golden(
      CompiledPolicy policy, Map<String, Object> c, Map<String, Object> defaults, String kind) {
    Map<String, Object> in = (Map<String, Object>) c.get("dau_vao");
    Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) defaults.get(kind));
    vars.putAll(in);
    List<Map<String, Object>> tasks = new ArrayList<>();
    for (Object t : (List<?>) in.get("nhiem_vu")) {
      Map<String, Object> nv = new LinkedHashMap<>((Map<String, Object>) defaults.get("nhiem_vu"));
      nv.putAll((Map<String, Object>) t);
      tasks.add(nv);
    }
    vars.put("nhiem_vu", tasks);
    Map<String, Object> overrides =
        c.get("tham_so") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    ScoreCalculator.Calculation calc =
        calculator.calculate(policy, CanonicalDoc.of(vars), vars, overrides);
    RatingClassifier.Rating r = classifier.classify(policy, calc);
    String id = String.valueOf(c.get("id"));
    String pack = policy.pinned().packCode();
    synchronized (ACTUALS) {
      ACTUALS.computeIfAbsent(pack, k -> new TreeMap<>()).put(id, r.explanation());
    }

    Map<String, Object> want = (Map<String, Object>) c.get("mong_doi");
    if (want.get("dong_dmn") != null) {
      assertThat(r.ruleId()).as("%s dòng", id).isEqualTo(want.get("dong_dmn"));
    }
    if (want.get("xep_loai") != null) {
      assertThat(r.tableLevel()).as("%s mức của bảng", id).isEqualTo(want.get("xep_loai"));
    }
    Object finalLevel = want.containsKey("muc_cuoi") ? want.get("muc_cuoi") : null;
    if (finalLevel == null && !"QUY".equals(kind)) {
      finalLevel = want.get("xep_loai");
    }
    if (finalLevel != null) {
      assertThat(r.level()).as("%s mức cuối", id).isEqualTo(finalLevel);
    }
    if (want.get("ha_muc") instanceof Boolean lowered) {
      assertThat(r.lowered()).as("%s hạ mức", id).isEqualTo(lowered);
    }
    if (want.get("dieu_kien_thieu") instanceof List<?> missing) {
      assertThat(r.missing()).as("%s điều kiện thiếu", id).isEqualTo(missing);
    }
    if (want.get("nguoi_dung_dau") instanceof Boolean head) {
      assertThat(r.outputs().get("nguoi_dung_dau")).as("%s người đứng đầu", id).isEqualTo(head);
    }
    assertThat(r.explanation())
        .as("%s câu giải thích", id)
        .isEqualTo(expected.getOrDefault(pack, Map.of()).get(id));
    // Vết xếp loại nối tiếp vết tính điểm, câu giải thích ở bước bảng xếp loại.
    assertThat(r.steps().get(0).stepNo()).isEqualTo(calc.trace().size() + 1);
    assertThat(r.steps().get(0).explanation()).isEqualTo(r.explanation());
  }

  @Test
  void viDuHaMucCuaTl02b() {
    // TL-02b QT-09: đủ điểm mức xuất sắc nhưng tỷ lệ nhiệm vụ vượt mục tiêu dưới 30%.
    ScoreCalculator.Calculation calc = calculator.calculate(dang, input("DANG", PARTY));
    RatingClassifier.Rating r = classifier.classify(dang, calc);
    assertThat(r.lowered()).isTrue();
    assertThat(r.level()).isEqualTo("HTTNV");
    assertThat(r.explanation())
        .startsWith("Đủ điểm mức Hoàn thành xuất sắc nhiệm vụ (100 ≥ 90) nhưng chưa đạt điều kiện")
        .contains(
            "tỷ lệ nhiệm vụ vượt mục tiêu tối thiểu 30% (thực tế 25%, theo QĐ Điều 11; PL A4)")
        .endsWith("Bộ quy định đánh giá 2.1.0.");
  }

  @Test
  void hoSoHaiLuongRaHaiKetQuaRieng() {
    ProfileScorer scorer = new ProfileScorer(calculator, classifier);
    Map<String, Object> attrs = PARTY;
    ComputedScore party = scorer.score(UUID.randomUUID(), dang, input("DANG", attrs));
    ComputedScore state =
        scorer.score(
            UUID.randomUUID(),
            nhaNuoc,
            input("NHA_NUOC", Map.of("ty_le_dap_ung_tieu_chi_chung", 1.0)));

    assertThat(party.flow()).isEqualTo("DANG");
    assertThat(state.flow()).isEqualTo("NHA_NUOC");
    assertThat(party.input().hashHex()).isNotEqualTo(state.input().hashHex());
    assertThat(party.autoRatingCode()).isEqualTo("HTTNV");
    assertThat(state.autoRatingCode()).isEqualTo("HTXSNV");
    assertThat(state.totalScore()).isEqualByComparingTo(BigDecimal.valueOf(100));
    assertThat(party.result().json())
        .contains("\"ha_muc\":true")
        .contains("\"dieu_kien_thieu\":[\"dat_ty_le_vuot\"]")
        .contains("\"luong\":\"DANG\"")
        .contains("\"muc_he_thong\":\"HTTNV\"");
    assertThat(state.result().json())
        .contains("\"dong_dmn\":\"xep-loai-nha-nuoc-dong-1\"")
        .contains("Bộ quy định đánh giá 1.4.0.");
    // Vết đánh số liên tục, bước cuối của luồng Đảng là mức cuối.
    assertThat(party.trace().get(party.trace().size() - 1).ruleCode()).isEqualTo("muc_cuoi");

    // Cùng đầu vào, cùng gói cho cùng kết quả (điều kiện để ghi luỹ đẳng).
    ComputedScore again = scorer.score(UUID.randomUUID(), dang, input("DANG", attrs));
    assertThat(again.result().hashHex()).isEqualTo(party.result().hashHex());
    assertThat(again.input().hashHex()).isEqualTo(party.input().hashHex());
  }

  @Test
  void bamDauVaoNamTaiLapTuBonBamQuy() {
    AnnualInput year = year(new BigDecimal("98"), "a".repeat(64));
    Map<String, Object> params = ProfileScorer.annualParams(dang);
    assertThat(params)
        .containsEntry("nam.cach_tinh", "TRUNG_BINH_QUY")
        .containsEntry("htxs.nguong_ty_le_vuot", 0.3);

    // Dựng lại tài liệu độc lập theo định nghĩa ở AnnualInput.
    List<Map<String, Object>> quarters = new ArrayList<>();
    String[] hashes = {"1".repeat(64), "2".repeat(64), "3".repeat(64), "a".repeat(64)};
    int[] scores = {92, 94, 96, 98};
    for (int i = 0; i < 4; i++) {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("quy", i + 1);
      q.put("diem", (double) scores[i]);
      q.put("xep_loai", "HTXSNV");
      q.put("co_xep_loai", true);
      q.put("input_hash", hashes[i]);
      q.put("policy_version", i < 2 ? "2.0.0" : "2.1.0");
      quarters.add(q);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("profile_id", PROFILE.toString());
    doc.put("person_id", PERSON.toString());
    doc.put("flow", "DANG");
    doc.put("ky", "NAM");
    doc.put("ket_qua_quy", quarters);
    doc.put("thuoc_tinh", new TreeMap<>(YEAR));
    doc.put("tham_so_nam", new TreeMap<>(params));
    assertThat(year.document(params).hashHex())
        .isEqualTo(
            vn.viettel.okrkpi.shared.kernel.Jcs.hex(
                vn.viettel.okrkpi.shared.kernel.Jcs.sha256(
                    vn.viettel.okrkpi.shared.kernel.Jcs.write(doc))));
    // Đổi băm một quý thì băm năm đổi; phiên bản gói của quý cũ được giữ (BR-10-06).
    assertThat(year(new BigDecimal("98"), "b".repeat(64)).document(params).hashHex())
        .isNotEqualTo(year.document(params).hashHex());
    assertThat(year.document(params).json()).contains("\"policy_version\":\"2.0.0\"");
  }

  @Test
  void quyIvDieuChinhThiDiemNamDoiVaMangKetQuaTruoc() {
    ProfileScorer scorer = new ProfileScorer(calculator, classifier);
    ComputedScore before =
        scorer.scoreAnnual(
            UUID.randomUUID(), dang, year(new BigDecimal("98"), "a".repeat(64)), null);
    assertThat(before.totalScore()).isEqualByComparingTo("95");
    assertThat(before.autoRatingCode()).isEqualTo("HTXSNV");
    assertThat(before.result().json()).doesNotContain("ket_qua_truoc").contains("\"ky\":\"NAM\"");

    // Quý IV điều chỉnh sau hậu kiểm: còn 58 điểm, xếp Không hoàn thành nhiệm vụ.
    AnnualInput adjusted =
        new AnnualInput(
            PROFILE,
            PERSON,
            "DANG",
            List.of(
                quarter(1, "92", "HTXSNV", "1".repeat(64), "2.0.0"),
                quarter(2, "94", "HTXSNV", "2".repeat(64), "2.0.0"),
                quarter(3, "96", "HTXSNV", "3".repeat(64), "2.1.0"),
                quarter(4, "58", "KHTNV", "c".repeat(64), "2.1.0")),
            YEAR);
    ComputedScore after =
        scorer.scoreAnnual(
            UUID.randomUUID(),
            dang,
            adjusted,
            new ProfileScorer.Previous(before.totalScore(), before.autoRatingCode()));
    assertThat(after.input().hashHex()).isNotEqualTo(before.input().hashHex());
    assertThat(after.totalScore()).isEqualByComparingTo("85");
    assertThat(after.autoRatingCode()).isEqualTo("HTTNV");
    assertThat(after.result().json())
        .contains("\"ket_qua_truoc\":{\"diem\":95,\"muc\":\"HTXSNV\"}")
        .contains("\"so_quy_khtnv\":1");

    // Tính lại cùng dữ liệu không mang kết quả trước khi không có gì đổi.
    ComputedScore same =
        scorer.scoreAnnual(
            UUID.randomUUID(),
            dang,
            adjusted,
            new ProfileScorer.Previous(after.totalScore(), after.autoRatingCode()));
    assertThat(same.result().json()).doesNotContain("ket_qua_truoc");
    assertThat(same.input().hashHex()).isEqualTo(after.input().hashHex());
  }

  @Test
  void goiKhacLuongThiTuChoi() {
    ProfileScorer scorer = new ProfileScorer(calculator, classifier);
    assertThatThrownBy(
            () ->
                scorer.score(
                    UUID.randomUUID(),
                    nhaNuoc,
                    input("DANG", Map.of("ty_le_dap_ung_tieu_chi_chung", 1.0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NHA_NUOC");
  }

  @Test
  void dinhDangSoKieuViet() {
    assertThat(RatingClassifier.number(98.8)).isEqualTo("98,8");
    assertThat(RatingClassifier.number(90)).isEqualTo("90");
    assertThat(RatingClassifier.number(1.005)).isEqualTo("1,01");
    assertThat(RatingClassifier.percent(0.6667)).isEqualTo("66,67%");
    assertThat(RatingClassifier.percent(0.3)).isEqualTo("30%");
  }

  /** Biến năm luồng Đảng đủ mọi điều kiện xuất sắc năm. */
  private static final Map<String, Object> YEAR =
      Map.of(
          "diem_toan_bo_nhiem_vu_nam",
          0.0,
          "ty_le_O_nam",
          1.0,
          "ty_le_vuot_nam",
          0.35,
          "giai_phap_dot_pha_da_kiem_chung",
          true,
          "ty_le_dap_ung_tieu_chi_chung",
          1.0,
          "loai_tru",
          false,
          "truot_then_chot_chu_quan_nam",
          false,
          "khong_xet_htxsnv_nam",
          false);

  private static AnnualInput year(BigDecimal q4, String q4Hash) {
    return new AnnualInput(
        PROFILE,
        PERSON,
        "DANG",
        List.of(
            quarter(4, q4.toPlainString(), "HTXSNV", q4Hash, "2.1.0"),
            quarter(1, "92", "HTXSNV", "1".repeat(64), "2.0.0"),
            quarter(2, "94", "HTXSNV", "2".repeat(64), "2.0.0"),
            quarter(3, "96", "HTXSNV", "3".repeat(64), "2.1.0")),
        YEAR);
  }

  private static QuarterResult quarter(
      int q, String score, String rating, String hash, String version) {
    return new QuarterResult(q, new BigDecimal(score), rating, true, hash, version);
  }

  /** Bốn nhiệm vụ, một nhiệm vụ vượt kế hoạch (25%), đủ chất lượng, đúng hạn. */
  private static ScoringInput input(String flow, Map<String, Object> attrs) {
    List<TaskInput> tasks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      List<ProductInput> products = new ArrayList<>();
      int approved = i == 0 ? 12 : 10;
      for (int p = 0; p < approved; p++) {
        products.add(
            new ProductInput(new UUID(i, p), ProductInput.CONFIRMED, BigDecimal.ONE, false, 0));
      }
      tasks.add(
          new TaskInput(
              UUID.nameUUIDFromBytes(("NV-" + i).getBytes(StandardCharsets.UTF_8)),
              "NV-" + i,
              i < 2 ? "THUONG_XUYEN" : "THEN_CHOT",
              10,
              BigDecimal.TEN,
              false,
              products,
              List.of()));
    }
    return new ScoringInput(PROFILE, PERSON, flow, tasks, attrs);
  }
}
