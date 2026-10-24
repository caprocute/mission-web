package vn.viettel.okrkpi.task.application.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.domain.catalog.CatalogRules;

/**
 * Bộ kiểm tra kế hoạch quý BR-04-02..BR-04-07 (TL-02a QT-04 bước 4, CORE-02-06), chạy được cả với
 * dòng chưa lưu và không ghi CSDL. BR-04-03, BR-04-04 là biểu thức CEL dùng chung với cel-js qua bộ
 * ca {@code data/golden/plan-rules/}; các quy tắc còn lại cần cả bảng kế hoạch nên viết bằng Java.
 *
 * <p>BR-04-06: điểm từng dòng là trọng số trong nhóm, điểm nhóm tối đa bằng điểm tối đa của nhóm
 * trong Policy Pack (TL-02 QT-04 ví dụ BR-04-06 và SCR-0403: thêm, bỏ dòng không làm nhóm vượt điểm
 * tối đa). Nhóm có dòng đã chấm điểm đạt đủ điểm tối đa; nhóm chưa có dòng nào đã chấm điểm thì
 * cảnh báo "chưa đạt". Trọng số dòng không thể làm nhóm vượt tỷ trọng nên kế hoạch không bị chặn vì
 * quy tắc này (trước đây so phần điểm của nhóm trên tổng mọi dòng, khiến mọi kế hoạch lệch khỏi
 * đúng tỷ lệ của gói bị chặn).
 */
public class PlanChecker {

  /** BR-04-04, cùng biểu thức với {@code data/golden/plan-rules/plan-rules.yaml}. */
  public static final String BR_04_04_CEL =
      "san_pham.size() > 0 && so_luong > 0.0 && don_vi_tinh.size() > 0 && han.size() > 0"
          + " && han >= dau_ky && han <= cuoi_ky";

  /** BR-04-03, cùng biểu thức với {@code data/golden/plan-rules/plan-rules.yaml}. */
  public static final String BR_04_03_CEL =
      "!co_tu_mo_ho || (san_pham.size() > 0 && don_vi_tinh.size() > 0 && han.size() > 0)";

  public enum Level {
    CHAN,
    CANH_BAO
  }

  /** Một kết quả kiểm tra ({@code PlanCheck} của hợp đồng). */
  public record Check(String rule, Level level, Integer lineNo, String message, String msg) {}

  /** Nhóm điểm của Policy Pack ({@code params.nhom}): mã, điểm tối đa, nhóm nhiệm vụ thuộc nhóm. */
  public record GroupWeight(String code, BigDecimal maxPoints, Set<String> taskGroups) {}

  /** KR đã duyệt của đơn vị trong kỳ. */
  public record KeyResultRef(UUID id, String title) {}

  /** Ngữ cảnh kiểm tra của một kế hoạch. */
  public record Context(
      LocalDate periodStart,
      LocalDate periodEnd,
      Collection<String> vagueWords,
      List<GroupWeight> groups,
      List<KeyResultRef> approvedKeyResults) {}

  /** Nhóm mặc định 30/70 của gói mẫu khi tenant chưa có Policy Pack. */
  public static final List<GroupWeight> DEFAULT_GROUPS =
      List.of(
          new GroupWeight("THUONG_XUYEN", BigDecimal.valueOf(30), Set.of("THUONG_XUYEN")),
          new GroupWeight("THEN_CHOT", BigDecimal.valueOf(70), Set.of("THEN_CHOT")));

  private static final Map<String, String> GROUP_LABELS =
      Map.of(
          "THUONG_XUYEN", "thường xuyên",
          "THEN_CHOT", "then chốt",
          "KET_QUA_NHIEM_VU", "kết quả nhiệm vụ");

  private static final Map<String, CelValueType> COMPLETE_VARS =
      Map.of(
          "san_pham", CelValueType.STRING,
          "so_luong", CelValueType.DOUBLE,
          "don_vi_tinh", CelValueType.STRING,
          "han", CelValueType.STRING,
          "dau_ky", CelValueType.STRING,
          "cuoi_ky", CelValueType.STRING);

  private static final Map<String, CelValueType> VAGUE_VARS =
      Map.of(
          "co_tu_mo_ho", CelValueType.BOOL,
          "san_pham", CelValueType.STRING,
          "don_vi_tinh", CelValueType.STRING,
          "han", CelValueType.STRING);

  private final CelEngine cel;
  private final CelCompiledProgram complete;
  private final CelCompiledProgram vague;

  public PlanChecker(CelEngine cel) {
    this.cel = Objects.requireNonNull(cel, "cel");
    this.complete = cel.compile(BR_04_04_CEL, COMPLETE_VARS, CelValueType.BOOL);
    this.vague = cel.compile(BR_04_03_CEL, VAGUE_VARS, CelValueType.BOOL);
  }

  public List<Check> check(List<ItemRow> items, Context ctx) {
    List<Check> out = new ArrayList<>();
    Set<UUID> referencedKrs = new HashSet<>();
    for (ItemRow i : items) {
      int n = i.itemNo();
      lead(i, n, out);
      Map<String, Object> vars = variables(i, ctx);
      String word = CatalogRules.vagueWord(i.title(), ctx.vagueWords());
      Map<String, Object> vagueVars = new HashMap<>(vars);
      vagueVars.keySet().retainAll(VAGUE_VARS.keySet());
      vagueVars.put("co_tu_mo_ho", word != null);
      if (!Boolean.TRUE.equals(cel.evaluate(vague, vagueVars))) {
        out.add(
            new Check(
                "BR-04-03",
                Level.CHAN,
                n,
                "KPI mơ hồ ở dòng "
                    + n
                    + ": \""
                    + word
                    + "\". Vui lòng ghi rõ sản phẩm, đơn vị tính và hạn.",
                "MSG-04-04"));
      }
      Map<String, Object> completeVars = new HashMap<>(vars);
      completeVars.keySet().retainAll(COMPLETE_VARS.keySet());
      if (!Boolean.TRUE.equals(cel.evaluate(complete, completeVars))) {
        out.add(
            new Check(
                "BR-04-04",
                Level.CHAN,
                n,
                "Dòng " + n + " cần đủ sản phẩm, số lượng, đơn vị tính và hạn trong kỳ.",
                null));
      }
      if (i.catalogItemId() == null && i.standardScore() == null) {
        out.add(
            new Check(
                "BR-04-05",
                Level.CANH_BAO,
                n,
                "Dòng " + n + " là việc ngoài danh mục, cấp duyệt sẽ chấm điểm khi duyệt kế hoạch.",
                null));
      }
      if (i.keyResultId() != null) {
        referencedKrs.add(i.keyResultId());
      }
    }
    groupWeights(items, ctx.groups(), out);
    for (KeyResultRef kr : ctx.approvedKeyResults()) {
      if (!referencedKrs.contains(kr.id())) {
        out.add(
            new Check(
                "BR-04-07",
                Level.CANH_BAO,
                null,
                "Kết quả then chốt \"" + kr.title() + "\" chưa có công việc nào trong kế hoạch.",
                null));
      }
    }
    return out;
  }

  /** Có kết quả mức chặn (dùng khi gửi duyệt, CORE-02-08). */
  public static boolean blocking(List<Check> checks) {
    return checks.stream().anyMatch(c -> c.level() == Level.CHAN);
  }

  private static void lead(ItemRow i, int n, List<Check> out) {
    int leads = i.lead() == null || i.lead().id() == null ? 0 : 1;
    if (leads != 1) {
      out.add(
          new Check(
              "BR-04-02",
              Level.CHAN,
              n,
              "Dòng " + n + " có " + leads + " đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì.",
              "MSG-04-05"));
      return;
    }
    List<Assignee> co = i.coAssignees() == null ? List.of() : i.coAssignees();
    if (co.stream().anyMatch(a -> i.lead().id().equals(a.id()))) {
      out.add(
          new Check(
              "BR-04-02",
              Level.CHAN,
              n,
              "Dòng " + n + " có 2 đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì.",
              "MSG-04-05"));
    }
  }

  private static Map<String, Object> variables(ItemRow i, Context ctx) {
    Map<String, Object> v = new HashMap<>();
    v.put("san_pham", blank(i.productName()));
    v.put("so_luong", i.quantity() == null ? 0.0 : i.quantity().doubleValue());
    v.put("don_vi_tinh", blank(i.unit()));
    v.put("han", i.dueDate() == null ? "" : i.dueDate().toString());
    v.put("dau_ky", ctx.periodStart().toString());
    v.put("cuoi_ky", ctx.periodEnd().toString());
    return v;
  }

  private static void groupWeights(List<ItemRow> items, List<GroupWeight> groups, List<Check> out) {
    if (items.stream().allMatch(i -> i.standardScore() == null)) {
      return;
    }
    for (GroupWeight g : groups) {
      boolean scored =
          items.stream()
              .anyMatch(
                  i ->
                      i.standardScore() != null
                          && i.standardScore().signum() > 0
                          && g.taskGroups().contains(i.taskGroup()));
      if (!scored) {
        String label = GROUP_LABELS.getOrDefault(g.code(), "nhiệm vụ");
        out.add(
            new Check(
                "BR-04-06",
                Level.CANH_BAO,
                null,
                "Tổng điểm nhóm "
                    + label
                    + " là 0, chưa đạt điểm tối đa "
                    + plain(g.maxPoints())
                    + ".",
                null));
      }
    }
  }

  private static String plain(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString().replace('.', ',');
  }

  private static String blank(String s) {
    return s == null ? "" : s.strip();
  }
}
