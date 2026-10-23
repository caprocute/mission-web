package vn.viettel.okrkpi.scoring.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tài liệu đầu vào chuẩn của một hồ sơ trong một luồng (TL-03 {@code score_input_v.input_doc}):
 * danh sách nhiệm vụ đã chuẩn hoá và thuộc tính cấp hồ sơ theo {@code inputs} của Policy Pack (tỷ
 * lệ mục tiêu O, tỷ lệ đáp ứng tiêu chí chung...). Cùng nội dung cho cùng {@code input_hash} nên
 * kết quả cũ dùng lại được.
 *
 * @param attributes biến cấp hồ sơ (chuỗi, số, boolean); không chứa dữ liệu C2
 */
public record ScoringInput(
    UUID profileId,
    UUID personId,
    String flow,
    List<TaskInput> tasks,
    Map<String, Object> attributes) {

  /** Biến danh sách nhiệm vụ của gói. */
  public static final String TASKS = "nhiem_vu";

  public ScoringInput {
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(personId, "personId");
    if (!"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw new IllegalArgumentException("Luồng đánh giá không hợp lệ: " + flow);
    }
    tasks = List.copyOf(tasks);
    attributes = Map.copyOf(attributes);
    if (attributes.containsKey(TASKS)) {
      throw new IllegalArgumentException("Thuộc tính hồ sơ không được trùng tên " + TASKS);
    }
    for (Object v : attributes.values()) {
      if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
        throw new IllegalArgumentException("Thuộc tính hồ sơ chỉ nhận chuỗi, số hoặc boolean");
      }
    }
  }

  /** Biến CEL của hồ sơ: thuộc tính và {@code nhiem_vu} theo thứ tự đầu vào. */
  public Map<String, Object> variables() {
    Map<String, Object> vars = new LinkedHashMap<>(new TreeMap<>(attributes));
    List<Map<String, Object>> list = new ArrayList<>();
    tasks.forEach(t -> list.add(t.toNhiemVu()));
    vars.put(TASKS, list);
    return vars;
  }

  /** Tài liệu {@code input_doc} chuẩn hoá. */
  public CanonicalDoc document() {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("profile_id", profileId.toString());
    doc.put("person_id", personId.toString());
    doc.put("flow", flow);
    doc.putAll(variables());
    return CanonicalDoc.of(doc);
  }
}
