package vn.viettel.okrkpi.scoring.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cấu trúc một bảng quyết định DMN của Policy Pack (TL-01 "bảng quyết định DMN"): cột đầu vào, dòng
 * (ô FEEL theo cột, đầu ra, chú thích như {@code legal_ref}, {@code dieu_khoan}, {@code
 * giai_thich}, {@code mau_ha_muc}) theo thứ tự. Flowable chọn dòng; cấu trúc này dùng để tìm điều
 * kiện còn thiếu và ghép câu giải thích (BR-09-10).
 */
public record DecisionTable(String key, String hitPolicy, List<Column> columns, List<Rule> rules) {

  /** Cột đầu vào: biểu thức đầu vào (tên biến), nhãn, kiểu FEEL. */
  public record Column(String name, String label, String type) {}

  /** Một dòng; {@code cells} theo thứ tự cột, {@code output} là văn bản ô đầu ra đầu tiên. */
  public record Rule(
      String id,
      String description,
      List<String> cells,
      String output,
      Map<String, String> annotations) {
    public Rule {
      cells = List.copyOf(cells);
      annotations = Map.copyOf(annotations);
    }

    /** Chú thích theo tên; {@code -} hay rỗng coi như không có. */
    public String annotation(String name) {
      String v = annotations.get(name);
      return v == null || v.isBlank() || "-".equals(v.strip()) ? null : v.strip();
    }

    /** Đầu ra là chuỗi FEEL {@code "X"} thì trả {@code X}. */
    public String outputValue() {
      if (output == null) {
        return null;
      }
      String s = output.strip();
      return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
          ? s.substring(1, s.length() - 1)
          : s;
    }
  }

  public DecisionTable {
    Objects.requireNonNull(key, "key");
    columns = List.copyOf(columns);
    rules = List.copyOf(rules);
  }
}
