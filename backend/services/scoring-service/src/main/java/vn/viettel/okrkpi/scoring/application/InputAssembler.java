package vn.viettel.okrkpi.scoring.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput.WorkItem;
import vn.viettel.okrkpi.scoring.domain.DelaySegment;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;

/**
 * Dựng {@link ScoringInput} của một hồ sơ từ dữ liệu lõi ({@code AppraisalScoringInput}) theo khai
 * báo {@code inputs} của Policy Pack (DV-07-04, DV-07-06). Biến hồ sơ được chọn theo {@code source}
 * của khai báo; nguồn mà hợp đồng lõi chưa cấp (ví dụ {@code okr.aggregate.objective_ratio}) lấy
 * giá trị {@code default} của khai báo, nếu không có thì giá trị trung tính theo kiểu ({@code
 * false}, {@code 0}, phần tử đầu của {@code enum} hoặc chuỗi rỗng). Cách ánh xạ việc lá sang {@code
 * NhiemVu} là suy luận, ghi ở wiki trang 14.
 */
public class InputAssembler {

  static final String HOAN_THANH = "HOAN_THANH";
  static final String THEN_CHOT = "THEN_CHOT";
  static final String THUONG_XUYEN = "THUONG_XUYEN";

  /** Nguồn thuộc tính hồ sơ mà dữ liệu lõi hiện cấp được. */
  private static final Map<String, Function<CoreScoringInput, Object>> SOURCES = sources();

  /** Đầu vào của hồ sơ và số biến phải lấy giá trị mặc định. */
  public record Assembled(ScoringInput input, List<String> defaulted) {}

  /**
   * @throws IllegalArgumentException dữ liệu lõi sai miền (ví dụ mức chất lượng ngoài năm mức)
   */
  public Assembled assemble(CompiledPolicy policy, CoreScoringInput in) {
    Map<String, Object> inputs = map(policy.manifest().get("inputs"));
    Map<String, Object> attributes = new LinkedHashMap<>();
    List<String> defaulted = new ArrayList<>();
    inputs.forEach(
        (name, declaration) -> {
          if (ScoringInput.TASKS.equals(name)) {
            return;
          }
          Map<String, Object> decl = map(declaration);
          Object type = decl.get("type");
          if (type instanceof String t && t.startsWith("list")) {
            return;
          }
          Function<CoreScoringInput, Object> source =
              SOURCES.get(String.valueOf(decl.get("source")));
          Object value = source == null ? null : source.apply(in);
          if (value == null) {
            value = fallback(decl);
            defaulted.add(name);
          }
          attributes.put(name, coerce(type, value));
        });
    List<TaskInput> tasks = new ArrayList<>();
    for (WorkItem w : in.workItems()) {
      if (counted(w)) {
        tasks.add(task(w));
      }
    }
    return new Assembled(
        new ScoringInput(in.profileId(), in.personId(), in.flow(), tasks, attributes),
        List.copyOf(defaulted));
  }

  /** Việc nháp và việc lùi, dừng theo quyết định của cấp có thẩm quyền không được tính. */
  static boolean counted(WorkItem w) {
    return !"NHAP".equals(w.statusAtLock()) && !w.deferredByDecision();
  }

  /**
   * Một việc lá thành một {@code NhiemVu}: giao một sản phẩm, sản phẩm được đếm khi đã nghiệm thu
   * có mức chất lượng, ngày chậm chủ quan và khách quan tách hai khoảng (ngày lịch lấy bằng ngày
   * làm việc vì hợp đồng chỉ cấp ngày làm việc), không hoàn thành chủ quan khi việc then chốt chưa
   * hoàn thành.
   */
  static TaskInput task(WorkItem w) {
    String group = THEN_CHOT.equals(w.taskGroup()) ? THEN_CHOT : THUONG_XUYEN;
    List<ProductInput> products =
        w.qualityLevel() == null
            ? List.of()
            : List.of(
                new ProductInput(w.taskId(), ProductInput.CONFIRMED, w.qualityLevel(), false, 0));
    List<DelaySegment> delays = new ArrayList<>();
    if (w.subjectiveLateWorkdays() > 0) {
      delays.add(
          new DelaySegment(
              w.subjectiveLateWorkdays(), w.subjectiveLateWorkdays(), DelaySegment.Cause.CHU_QUAN));
    }
    if (w.objectiveLateWorkdays() > 0) {
      delays.add(
          new DelaySegment(
              w.objectiveLateWorkdays(), w.objectiveLateWorkdays(), DelaySegment.Cause.KHACH_QUAN));
    }
    BigDecimal base = w.standardScore();
    if (base != null) {
      base = base.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
    }
    return new TaskInput(
        w.taskId(),
        w.axisCode() == null ? "" : w.axisCode(),
        group,
        1,
        base,
        THEN_CHOT.equals(group) && !HOAN_THANH.equals(w.statusAtLock()),
        products,
        delays);
  }

  private static Map<String, Function<CoreScoringInput, Object>> sources() {
    Map<String, Function<CoreScoringInput, Object>> m = new LinkedHashMap<>();
    m.put("org.aggregate.is_unit_head", CoreScoringInput::head);
    m.put("org.aggregate.holds_position", CoreScoringInput::leadership);
    m.put(
        "appraisal.aggregate.subject_group",
        in -> in.leadership() ? "LANH_DAO" : "KHONG_GIU_CHUC_VU");
    m.put("appraisal.aggregate.general_criteria_ratio", InputAssembler::criteriaRatio);
    m.put("appraisal.aggregate.exclusion_flag", in -> in.incidents() > 0);
    m.put(
        "appraisal.aggregate.has_key_work",
        in ->
            in.workItems().stream().filter(InputAssembler::counted).anyMatch(InputAssembler::key));
    m.put(
        "appraisal.aggregate.all_key_work_completed",
        in ->
            in.workItems().stream()
                .filter(InputAssembler::counted)
                .filter(InputAssembler::key)
                .allMatch(w -> HOAN_THANH.equals(w.statusAtLock())));
    m.put(
        "appraisal.aggregate.key_missed_subjective",
        in ->
            in.workItems().stream()
                .filter(InputAssembler::counted)
                .anyMatch(
                    w ->
                        key(w)
                            && !HOAN_THANH.equals(w.statusAtLock())
                            && w.subjectiveLateWorkdays() > 0));
    m.put("appraisal.aggregate.exceeded_ratio", InputAssembler::exceededRatio);
    return Map.copyOf(m);
  }

  private static boolean key(WorkItem w) {
    return THEN_CHOT.equals(w.taskGroup());
  }

  private static Object criteriaRatio(CoreScoringInput in) {
    if (in.generalCriteria().isEmpty()) {
      return null;
    }
    long met = in.generalCriteria().stream().filter(CoreScoringInput.Criterion::met).count();
    return (double) met / in.generalCriteria().size();
  }

  private static Object exceededRatio(CoreScoringInput in) {
    List<WorkItem> items = in.workItems().stream().filter(InputAssembler::counted).toList();
    if (items.isEmpty()) {
      return null;
    }
    return (double) items.stream().filter(WorkItem::exceeded).count() / items.size();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static Object fallback(Map<String, Object> decl) {
    if (decl.get("default") != null) {
      return decl.get("default");
    }
    Object type = decl.get("type");
    if ("bool".equals(type)) {
      return false;
    }
    if ("int".equals(type) || "double".equals(type)) {
      return 0;
    }
    if (decl.get("enum") instanceof List<?> values && !values.isEmpty()) {
      return String.valueOf(values.get(0));
    }
    return "";
  }

  private static Object coerce(Object type, Object value) {
    if (value instanceof Number n) {
      if ("int".equals(type)) {
        return n.longValue();
      }
      if ("double".equals(type)) {
        return n.doubleValue();
      }
    }
    return value;
  }
}
