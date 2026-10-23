package vn.viettel.okrkpi.scoring.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Đầu vào xếp loại năm của một hồ sơ trong một luồng (DV-07-07, QT-10): kết quả các quý đã quyết
 * định và biến cấp năm theo {@code inputs_nam} của gói (tỷ lệ mục tiêu năm, tỷ lệ vượt năm...).
 * {@code input_hash} năm là băm JCS của danh sách quý (mỗi quý kèm {@code input_hash}, phiên bản
 * gói và mức đã quyết định), biến cấp năm và tham số năm, nên quý nào đổi thì băm năm đổi.
 *
 * @param attributes biến cấp năm (chuỗi, số, boolean); không chứa dữ liệu C2
 */
public record AnnualInput(
    UUID profileId,
    UUID personId,
    String flow,
    List<QuarterResult> quarters,
    Map<String, Object> attributes) {

  /** Biến danh sách quý của gói. */
  public static final String QUARTERS = "ket_qua_quy";

  public AnnualInput {
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(personId, "personId");
    if (!"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw new IllegalArgumentException("Luồng đánh giá không hợp lệ: " + flow);
    }
    List<QuarterResult> sorted = new ArrayList<>(quarters);
    sorted.sort(Comparator.comparingInt(QuarterResult::quarter));
    Set<Integer> seen = new HashSet<>();
    for (QuarterResult q : sorted) {
      if (!seen.add(q.quarter())) {
        throw new IllegalArgumentException("Trùng quý " + q.quarter());
      }
    }
    if (sorted.isEmpty()) {
      throw new IllegalArgumentException("Xếp loại năm cần ít nhất một quý");
    }
    quarters = List.copyOf(sorted);
    attributes = Map.copyOf(attributes);
    if (attributes.containsKey(QUARTERS)) {
      throw new IllegalArgumentException("Thuộc tính năm không được trùng tên " + QUARTERS);
    }
    for (Object v : attributes.values()) {
      if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
        throw new IllegalArgumentException("Thuộc tính năm chỉ nhận chuỗi, số hoặc boolean");
      }
    }
  }

  /** Biến CEL của hồ sơ năm: thuộc tính và {@code ket_qua_quy} theo thứ tự quý. */
  public Map<String, Object> variables() {
    Map<String, Object> vars = new LinkedHashMap<>(new TreeMap<>(attributes));
    List<Map<String, Object>> list = new ArrayList<>();
    quarters.forEach(q -> list.add(q.toKetQuaQuy()));
    vars.put(QUARTERS, list);
    return vars;
  }

  /**
   * Tài liệu {@code input_doc} năm chuẩn hoá.
   *
   * @param annualParams tham số năm mà gói dùng (khoá phẳng → giá trị)
   */
  public CanonicalDoc document(Map<String, Object> annualParams) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("profile_id", profileId.toString());
    doc.put("person_id", personId.toString());
    doc.put("flow", flow);
    doc.put("ky", "NAM");
    List<Map<String, Object>> list = new ArrayList<>();
    quarters.forEach(q -> list.add(q.document()));
    doc.put(QUARTERS, list);
    doc.put("thuoc_tinh", new TreeMap<>(attributes));
    doc.put("tham_so_nam", new TreeMap<>(annualParams));
    return CanonicalDoc.of(doc);
  }
}
