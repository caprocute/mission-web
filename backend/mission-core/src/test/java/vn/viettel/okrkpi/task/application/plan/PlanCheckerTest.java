package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Context;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.GroupWeight;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.KeyResultRef;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Level;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;

/** CORE-02-06: BR-04-02..BR-04-07 trên bảng kế hoạch chưa lưu. */
class PlanCheckerTest {

  private static final LocalDate START = LocalDate.parse("2027-01-01");
  private static final LocalDate END = LocalDate.parse("2027-03-31");
  private static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-0000000000a1");
  private static final UUID OTHER = UUID.fromString("01927a02-0000-7000-8000-0000000000b2");

  /** Thời hạn rộng: lần chạy đầu khi JVM lạnh có thể vượt 50 ms mặc định. */
  private static final CelEngine CEL =
      new CelEngine(
          StaticWorkingCalendar.weekendsOnly(),
          new CelSandboxOptions(
              CelSandboxOptions.DEFAULT_MAX_EXPRESSION_LENGTH,
              CelSandboxOptions.DEFAULT_MAX_AST_DEPTH,
              CelSandboxOptions.DEFAULT_MAX_AST_NODE_COUNT,
              CelSandboxOptions.DEFAULT_MAX_COMPREHENSION_ITERATIONS,
              Duration.ofSeconds(5),
              CelSandboxOptions.DEFAULT_PROGRAM_CACHE_MAX_SIZE));

  private final PlanChecker checker = new PlanChecker(CEL);

  private static ItemRow line(int n, String group, double score) {
    return new ItemRow(
        null,
        n,
        UUID.randomUUID(),
        "DM-" + n,
        null,
        "Báo cáo tổng hợp " + n,
        group,
        new Assignee("DON_VI", UNIT, ""),
        List.of(),
        "Báo cáo",
        BigDecimal.ONE,
        "báo cáo",
        2,
        BigDecimal.valueOf(score),
        LocalDate.parse("2027-03-20"),
        null,
        null,
        null);
  }

  private static ItemRow with(ItemRow i, Map<String, Object> f) {
    return new ItemRow(
        i.id(),
        i.itemNo(),
        (UUID) f.getOrDefault("catalog", i.catalogItemId()),
        i.catalogCode(),
        (UUID) f.getOrDefault("kr", i.keyResultId()),
        (String) f.getOrDefault("title", i.title()),
        i.taskGroup(),
        f.containsKey("lead") ? (Assignee) f.get("lead") : i.lead(),
        (List<Assignee>) f.getOrDefault("co", i.coAssignees()),
        (String) f.getOrDefault("product", i.productName()),
        (BigDecimal) f.getOrDefault("qty", i.quantity()),
        (String) f.getOrDefault("unit", i.unit()),
        i.difficulty(),
        f.containsKey("score") ? (BigDecimal) f.get("score") : i.standardScore(),
        f.containsKey("due") ? (LocalDate) f.get("due") : i.dueDate(),
        null,
        null,
        null);
  }

  private static Context ctx(List<KeyResultRef> krs) {
    return new Context(
        START, END, List.of("nâng cao", "tăng cường"), PlanChecker.DEFAULT_GROUPS, krs);
  }

  /** Kế hoạch cân đối 30/70, mọi dòng hợp lệ. */
  private static List<ItemRow> balanced() {
    return new ArrayList<>(
        List.of(line(1, "THUONG_XUYEN", 3), line(2, "THEN_CHOT", 3.5), line(3, "THEN_CHOT", 3.5)));
  }

  private List<Check> rule(List<ItemRow> items, String rule) {
    return checker.check(items, ctx(List.of())).stream()
        .filter(c -> c.rule().equals(rule))
        .toList();
  }

  @Test
  void keHoachHopLeKhongCoKetQua() {
    assertThat(checker.check(balanced(), ctx(List.of()))).isEmpty();
  }

  @Test
  void br0402MotChuTri() {
    List<ItemRow> items = balanced();
    items.set(0, with(items.get(0), Map.of("co", List.of(new Assignee("DON_VI", UNIT, "")))));
    Map<String, Object> noLead = new HashMap<>();
    noLead.put("lead", null);
    items.set(1, with(items.get(1), noLead));
    List<Check> out = rule(items, "BR-04-02");
    assertThat(out).extracting(Check::lineNo).containsExactly(1, 2);
    assertThat(out).allSatisfy(c -> assertThat(c.msg()).isEqualTo("MSG-04-05"));
    assertThat(out.get(0).message()).contains("có 2 đơn vị chủ trì");
    assertThat(out.get(1).message()).contains("có 0 đơn vị chủ trì");
    items.set(0, with(items.get(0), Map.of("co", List.of(new Assignee("DON_VI", OTHER, "")))));
    assertThat(rule(items, "BR-04-02")).hasSize(1);
  }

  @Test
  void br0403TuMoHoCanDuSanPhamDonViHan() {
    List<ItemRow> items = balanced();
    items.set(
        0, with(items.get(0), Map.of("title", "Tăng cường công tác tuyên truyền", "unit", "")));
    List<Check> out = rule(items, "BR-04-03");
    assertThat(out)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.level()).isEqualTo(Level.CHAN);
              assertThat(c.msg()).isEqualTo("MSG-04-04");
              assertThat(c.message()).contains("dòng 1").contains("tăng cường");
            });
    items.set(0, with(items.get(0), Map.of("unit", "đợt")));
    assertThat(rule(items, "BR-04-03")).isEmpty();
  }

  @Test
  void br0404DuTruongVaHanTrongKy() {
    List<ItemRow> items = balanced();
    items.set(0, with(items.get(0), Map.of("qty", BigDecimal.ZERO)));
    items.set(1, with(items.get(1), Map.of("due", LocalDate.parse("2027-04-01"))));
    items.set(2, with(items.get(2), Map.of("product", " ")));
    assertThat(rule(items, "BR-04-04")).extracting(Check::lineNo).containsExactly(1, 2, 3);
    assertThat(rule(balanced(), "BR-04-04")).isEmpty();
  }

  @Test
  void br0405NgoaiDanhMucChuaCoDiemLaCanhBao() {
    List<ItemRow> items = balanced();
    Map<String, Object> f = new HashMap<>();
    f.put("catalog", null);
    f.put("score", null);
    items.add(with(line(4, "THEN_CHOT", 1), f));
    assertThat(rule(items, "BR-04-05"))
        .singleElement()
        .satisfies(c -> assertThat(c.level()).isEqualTo(Level.CANH_BAO));
    f.remove("score");
    items.set(3, with(line(4, "THEN_CHOT", 1), f));
    assertThat(rule(items, "BR-04-05")).isEmpty();
  }

  @Test
  void br0406DiemDongLaTrongSoTrongNhomNenLechTyLeKhongBiChan() {
    // 25/75 theo điểm dòng: điểm dòng là trọng số trong nhóm, mỗi nhóm có việc đạt đủ điểm tối đa.
    List<ItemRow> items =
        List.of(line(1, "THUONG_XUYEN", 2.5), line(2, "THEN_CHOT", 40), line(3, "THEN_CHOT", 35));
    List<Check> out = rule(items, "BR-04-06");
    assertThat(out).isEmpty();
    assertThat(PlanChecker.blocking(out)).isFalse();
  }

  @Test
  void br0406NhomChuaCoViecLaCanhBaoKhongChan() {
    List<ItemRow> items = List.of(line(1, "THUONG_XUYEN", 5), line(2, "THUONG_XUYEN", 5));
    List<Check> out = rule(items, "BR-04-06");
    assertThat(out)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.level()).isEqualTo(Level.CANH_BAO);
              assertThat(c.message())
                  .isEqualTo("Tổng điểm nhóm then chốt là 0, chưa đạt điểm tối đa 70.");
            });
    assertThat(PlanChecker.blocking(out)).isFalse();
  }

  @Test
  void br0406MotNhomGopCuaGoiNd335KhongBaoLech() {
    List<GroupWeight> nd335 =
        List.of(
            new GroupWeight(
                "KET_QUA_NHIEM_VU", BigDecimal.valueOf(70), Set.of("THUONG_XUYEN", "THEN_CHOT")));
    List<ItemRow> items = List.of(line(1, "THUONG_XUYEN", 9), line(2, "THEN_CHOT", 1));
    assertThat(checker.check(items, new Context(START, END, List.of(), nd335, List.of())))
        .isEmpty();
  }

  @Test
  void br0407KrChuaCoViecLaCanhBao() {
    UUID kr1 = UUID.randomUUID();
    UUID kr2 = UUID.randomUUID();
    List<ItemRow> items = balanced();
    items.set(1, with(items.get(1), Map.of("kr", kr1)));
    List<Check> out =
        checker.check(
            items, ctx(List.of(new KeyResultRef(kr1, "KR một"), new KeyResultRef(kr2, "KR hai"))));
    assertThat(out)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.rule()).isEqualTo("BR-04-07");
              assertThat(c.level()).isEqualTo(Level.CANH_BAO);
              assertThat(c.message()).contains("KR hai");
            });
  }

  @Test
  void docNhomDiemTuPolicyPack() {
    assertThat(PlanService.weights(null)).isEqualTo(PlanChecker.DEFAULT_GROUPS);
    assertThat(PlanService.weights(List.of(Map.of("ma", "X"))))
        .isEqualTo(PlanChecker.DEFAULT_GROUPS);
    assertThat(
            PlanService.weights(
                List.of(
                    Map.of(
                        "ma",
                        "KET_QUA_NHIEM_VU",
                        "diem_toi_da",
                        70,
                        "nhom_nhiem_vu",
                        List.of("THUONG_XUYEN", "THEN_CHOT")),
                    Map.of("ma", "THEN_CHOT", "diem_toi_da", 12.5))))
        .containsExactly(
            new GroupWeight(
                "KET_QUA_NHIEM_VU",
                BigDecimal.valueOf(70).setScale(0),
                Set.of("THUONG_XUYEN", "THEN_CHOT")),
            new GroupWeight("THEN_CHOT", new BigDecimal("12.5"), Set.of("THEN_CHOT")));
  }

  @Test
  void baTramDongP95DuoiBaTramMs() {
    List<ItemRow> items = new ArrayList<>();
    for (int n = 1; n <= 300; n++) {
      items.add(line(n, n % 10 < 3 ? "THUONG_XUYEN" : "THEN_CHOT", 1));
    }
    Context c = ctx(List.of(new KeyResultRef(UUID.randomUUID(), "KR")));
    for (int i = 0; i < 5; i++) {
      checker.check(items, c);
    }
    long[] ms = new long[20];
    for (int i = 0; i < ms.length; i++) {
      long t = System.nanoTime();
      checker.check(items, c);
      ms[i] = (System.nanoTime() - t) / 1_000_000;
    }
    Arrays.sort(ms);
    assertThat(ms[18]).isLessThanOrEqualTo(300);
  }

  /**
   * Bộ ca vàng dùng chung với cel-js: biểu thức phải trùng hằng của PlanChecker và cho đúng kết
   * quả.
   */
  @Test
  @SuppressWarnings("unchecked")
  void boCaVangQuyTacDong() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("data/golden/plan-rules"))) {
      root = root.getParent();
    }
    Map<String, Object> suite =
        new Yaml().load(Files.readString(root.resolve("data/golden/plan-rules/plan-rules.yaml")));
    List<Map<String, Object>> cases = (List<Map<String, Object>>) suite.get("cases");
    assertThat(cases).hasSize(9);
    for (Map<String, Object> c : cases) {
      String expr = (String) c.get("expression");
      assertThat(expr)
          .as((String) c.get("id"))
          .isIn(PlanChecker.BR_04_04_CEL, PlanChecker.BR_04_03_CEL);
      Map<String, CelValueType> types = new HashMap<>();
      ((Map<String, String>) c.get("variableTypes"))
          .forEach((k, v) -> types.put(k, CelValueType.valueOf(v)));
      Object got =
          CEL.evaluate(
              CEL.compile(expr, types, CelValueType.valueOf((String) c.get("returnType"))),
              (Map<String, Object>) c.get("inputs"));
      assertThat(got).as((String) c.get("id")).isEqualTo(c.get("expected"));
    }
  }
}
