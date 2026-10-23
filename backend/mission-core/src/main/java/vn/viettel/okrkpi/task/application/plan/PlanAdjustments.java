package vn.viettel.okrkpi.task.application.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Level;
import vn.viettel.okrkpi.task.application.plan.port.PlanLineFacts.LineFact;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.RemovedItem;

/**
 * Quy tắc điều chỉnh kế hoạch (QT-04 bước 9, CORE-02-10): so sánh hai phiên bản theo {@code
 * origin_item_id}, lý do từng thay đổi (BR-04-10), không bỏ việc đã có minh chứng xác nhận
 * (BR-04-11), không đổi hạn nhiệm vụ đang thực hiện (BR-04-12), giới hạn số lần mỗi quý.
 */
public final class PlanAdjustments {

  public static final String BR_04_10 = "BR-04-10";
  public static final String BR_04_11 = "BR-04-11";
  public static final String BR_04_12 = "BR-04-12";

  /** Tham số Policy Pack: số lần điều chỉnh tối đa mỗi quý (TL-02a QT-04 bước 9, mặc định 2). */
  public static final String MAX_KEY = "plan_adjustment_max";

  /**
   * Tham số Policy Pack: hạn điều chỉnh là đầu tháng thứ {@code n} tính ngược từ tháng cuối kỳ; mặc
   * định 1 là "trước tháng cuối quý" (tên tham số là suy luận).
   */
  public static final String CUTOFF_KEY = "plan_adjustment_cutoff_months";

  public static final int DEFAULT_MAX = 2;
  public static final int DEFAULT_CUTOFF_MONTHS = 1;

  /** Trạng thái nhiệm vụ không được đổi hạn qua điều chỉnh (BR-04-12). */
  static final String IN_PROGRESS = "DANG_THUC_HIEN";

  /** Phân loại dòng ({@code PlanDiffLine.change}). */
  public enum Change {
    THEM,
    SUA,
    BO,
    KHONG_DOI
  }

  /** Một dòng so sánh; {@code before} rỗng với dòng thêm, {@code after} rỗng với dòng bỏ. */
  public record Line(
      Change change, ItemRow before, ItemRow after, List<String> changedFields, String reason) {}

  /** Kết quả so sánh phiên bản {@code versionNo} với {@code baseVersionNo}. */
  public record Diff(int baseVersionNo, int versionNo, List<Line> lines) {

    public int count(Change change) {
      return (int) lines.stream().filter(l -> l.change() == change).count();
    }
  }

  private PlanAdjustments() {}

  /**
   * Dòng mới có {@code originItemId} trỏ dòng của phiên bản gốc là dòng giữ lại (sửa hoặc không
   * đổi); không trỏ là dòng thêm; dòng gốc không còn dòng nào trỏ tới là dòng bỏ, lý do lấy từ
   * {@code removed}.
   */
  public static Diff diff(
      int baseVersionNo,
      int versionNo,
      List<ItemRow> base,
      List<ItemRow> items,
      List<RemovedItem> removed) {
    Map<UUID, ItemRow> byId = new LinkedHashMap<>();
    base.forEach(b -> byId.put(b.id(), b));
    Map<UUID, String> removedReasons =
        removed.stream()
            .filter(r -> r.originItemId() != null)
            .collect(
                Collectors.toMap(
                    RemovedItem::originItemId,
                    r -> Objects.toString(r.changeReason(), ""),
                    (a, b) -> a));
    List<Line> lines = new ArrayList<>(items.size() + base.size());
    Set<UUID> kept = new java.util.HashSet<>();
    for (ItemRow a : items) {
      ItemRow b = a.originItemId() == null ? null : byId.get(a.originItemId());
      if (b == null || !kept.add(b.id())) {
        lines.add(new Line(Change.THEM, null, a, List.of(), a.changeReason()));
        continue;
      }
      List<String> fields = changedFields(b, a);
      lines.add(
          new Line(
              fields.isEmpty() ? Change.KHONG_DOI : Change.SUA, b, a, fields, a.changeReason()));
    }
    for (ItemRow b : base) {
      if (!kept.contains(b.id())) {
        lines.add(new Line(Change.BO, b, null, List.of(), removedReasons.get(b.id())));
      }
    }
    return new Diff(baseVersionNo, versionNo, List.copyOf(lines));
  }

  /**
   * Trường nghiệp vụ khác nhau giữa hai dòng, theo tên trường của {@code PlanItem}. Sản phẩm, đơn
   * vị tính và điểm của dòng thuộc danh mục do danh mục quyết định nên chỉ so khi hai dòng cùng là
   * dòng ngoài danh mục hoặc cùng mục danh mục.
   */
  static List<String> changedFields(ItemRow b, ItemRow a) {
    List<String> out = new ArrayList<>();
    diff(out, "title", b.title(), a.title());
    diff(out, "taskGroup", b.taskGroup(), a.taskGroup());
    diff(out, "leadAssignee", key(b.lead()), key(a.lead()));
    diff(out, "coAssignees", keys(b.coAssignees()), keys(a.coAssignees()));
    diff(out, "catalogItemId", b.catalogItemId(), a.catalogItemId());
    diff(out, "keyResultId", b.keyResultId(), a.keyResultId());
    if (!same(b.quantity(), a.quantity())) {
      out.add("quantity");
    }
    diff(out, "unit", b.unit(), a.unit());
    diff(out, "dueDate", b.dueDate(), a.dueDate());
    diff(out, "recurrence", b.recurrence(), a.recurrence());
    if (a.catalogItemId() != null
        && Objects.equals(b.catalogItemId(), a.catalogItemId())
        && !same(b.standardScore(), a.standardScore())) {
      out.add("standardScore");
    }
    return List.copyOf(out);
  }

  /**
   * Lỗi chặn của phiên bản điều chỉnh: thiếu lý do (BR-04-10), bỏ việc đã có minh chứng xác nhận
   * (BR-04-11, MSG-04-11), đổi hạn nhiệm vụ đang thực hiện (BR-04-12, MSG-04-12). {@code facts}
   * theo mã dòng của phiên bản gốc.
   */
  public static List<Check> checks(Diff diff, Map<UUID, LineFact> facts) {
    List<Check> out = new ArrayList<>();
    for (Line l : diff.lines()) {
      LineFact fact = l.before() == null ? null : facts.get(l.before().id());
      switch (l.change()) {
        case THEM, SUA -> {
          if (blank(l.reason())) {
            out.add(
                new Check(
                    BR_04_10,
                    Level.CHAN,
                    l.after().itemNo(),
                    l.change() == Change.THEM
                        ? "Ghi lý do thêm công việc trong lần điều chỉnh"
                        : "Ghi lý do thay đổi công việc trong lần điều chỉnh",
                    null));
          }
          if (l.change() == Change.SUA
              && l.changedFields().contains("dueDate")
              && fact != null
              && IN_PROGRESS.equals(fact.taskStatus())) {
            out.add(
                new Check(
                    BR_04_12,
                    Level.CHAN,
                    l.after().itemNo(),
                    "Hạn của nhiệm vụ đang thực hiện không đổi qua điều chỉnh kế hoạch. Vui lòng"
                        + " dùng đề nghị gia hạn.",
                    "MSG-04-12"));
          }
        }
        case BO -> {
          if (blank(l.reason())) {
            out.add(
                new Check(
                    BR_04_10,
                    Level.CHAN,
                    null,
                    "Ghi lý do bỏ công việc \"" + l.before().title() + "\"",
                    null));
          }
          if (fact != null && fact.confirmedEvidence()) {
            out.add(
                new Check(
                    BR_04_11,
                    Level.CHAN,
                    null,
                    "Không bỏ được việc \""
                        + l.before().title()
                        + "\" vì đã có minh chứng được xác nhận.",
                    "MSG-04-11"));
          }
        }
        case KHONG_DOI -> {
          // Dòng giữ nguyên không cần lý do.
        }
      }
    }
    return out;
  }

  /** Lỗi bảo toàn kết quả (BR-04-11, BR-04-12) chặn cả khi lưu nháp. */
  public static boolean protectsResults(Check c) {
    return BR_04_11.equals(c.rule()) || BR_04_12.equals(c.rule());
  }

  /** Số lần được điều chỉnh: tham số của gói cộng số lần ngoại lệ người đứng đầu đã cấp. */
  public static int allowed(int max, int granted) {
    return Math.max(0, max) + Math.max(0, granted);
  }

  /** Ngày đầu tiên không còn được tạo điều chỉnh: đầu tháng thứ {@code months} tính từ cuối kỳ. */
  public static LocalDate cutoff(LocalDate periodEnd, int months) {
    return periodEnd.withDayOfMonth(1).minusMonths(Math.max(1, months) - 1L);
  }

  /** Nhóm nhiệm vụ có tổng điểm dòng khác nhau giữa hai phiên bản. */
  public static boolean groupWeightChanged(List<ItemRow> base, List<ItemRow> items) {
    return !totals(base).equals(totals(items));
  }

  private static Map<String, BigDecimal> totals(List<ItemRow> items) {
    Map<String, BigDecimal> out = new java.util.TreeMap<>();
    for (ItemRow i : items) {
      out.merge(
          Objects.toString(i.taskGroup(), ""),
          i.standardScore() == null ? BigDecimal.ZERO : i.standardScore().stripTrailingZeros(),
          BigDecimal::add);
    }
    out.replaceAll((k, v) -> v.stripTrailingZeros());
    return out;
  }

  private static void diff(List<String> out, String field, Object before, Object after) {
    if (!Objects.equals(before, after)) {
      out.add(field);
    }
  }

  private static boolean same(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }

  private static String key(Assignee a) {
    return a == null ? null : a.kind() + ":" + a.id();
  }

  private static Set<String> keys(List<Assignee> as) {
    return as == null
        ? Set.of()
        : as.stream().map(PlanAdjustments::key).collect(Collectors.toUnmodifiableSet());
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
