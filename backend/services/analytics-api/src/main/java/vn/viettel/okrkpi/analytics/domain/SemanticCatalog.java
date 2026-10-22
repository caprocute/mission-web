package vn.viettel.okrkpi.analytics.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lớp ngữ nghĩa công bố (BR-16-02): chỉ các chỉ số và chiều có ở đây mới được truy vấn hay đặt vào
 * widget. {@code supportedMeasures} của một chiều là các chỉ số đi được tới chiều đó qua quan hệ
 * của Cube (cùng cube hoặc cube có join tới cube của chiều).
 */
public record SemanticCatalog(
    String version, Map<String, Measure> measures, Map<String, Dimension> dimensions) {

  public record Measure(String name, String title, String type, String unit, boolean drillable) {}

  public record Dimension(String name, String title, String type, Set<String> supportedMeasures) {}

  /** Một vi phạm, đặt tên theo trường của yêu cầu (ví dụ {@code measures[1]}). */
  public record Violation(String field, String message, boolean combination) {}

  public SemanticCatalog {
    measures = new LinkedHashMap<>(measures);
    dimensions = new LinkedHashMap<>(dimensions);
  }

  /**
   * Kiểm tên chỉ số, chiều, bộ lọc và tổ hợp chỉ số với chiều. Vi phạm tổ hợp đánh dấu {@code
   * combination} để trả mã {@code ANALYTICS-422-INVALID-COMBINATION}.
   */
  public List<Violation> check(
      String prefix, List<String> ms, List<String> ds, List<Filter> filters) {
    List<Violation> out = new ArrayList<>();
    for (int i = 0; i < ms.size(); i++) {
      if (!measures.containsKey(ms.get(i))) {
        out.add(
            new Violation(
                prefix + "measures[" + i + "]", "Chỉ số không có trong lớp ngữ nghĩa", true));
      }
    }
    for (int i = 0; i < ds.size(); i++) {
      Dimension d = dimensions.get(ds.get(i));
      if (d == null) {
        out.add(
            new Violation(
                prefix + "dimensions[" + i + "]", "Chiều không có trong lớp ngữ nghĩa", true));
        continue;
      }
      for (String m : ms) {
        if (measures.containsKey(m) && !d.supportedMeasures().contains(m)) {
          out.add(
              new Violation(
                  prefix + "dimensions[" + i + "]",
                  "Chiều này không áp dụng cho chỉ số đã chọn",
                  true));
          break;
        }
      }
    }
    for (int i = 0; i < filters.size(); i++) {
      Filter f = filters.get(i);
      Dimension d = dimensions.get(f.member());
      if (d == null) {
        out.add(
            new Violation(
                prefix + "filters[" + i + "].member",
                "Chiều lọc không có trong lớp ngữ nghĩa",
                true));
      } else if (ms.stream()
          .anyMatch(m -> measures.containsKey(m) && !d.supportedMeasures().contains(m))) {
        out.add(
            new Violation(
                prefix + "filters[" + i + "].member",
                "Chiều lọc không áp dụng cho chỉ số đã chọn",
                true));
      }
      if (f.values().isEmpty()) {
        out.add(
            new Violation(
                prefix + "filters[" + i + "].values", "Cần ít nhất một giá trị lọc", false));
      }
    }
    return out;
  }
}
