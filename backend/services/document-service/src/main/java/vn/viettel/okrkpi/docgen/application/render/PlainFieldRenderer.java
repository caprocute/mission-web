package vn.viettel.okrkpi.docgen.application.render;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ánh xạ mặc định: trải phẳng dữ liệu thành khoá nối bằng dấu chấm ({@code canBo.hoTen}), phần tử
 * danh sách đánh số từ 1 ({@code mucTieu.1.ten}); giá trị {@code null} thành chuỗi rỗng.
 */
public final class PlainFieldRenderer implements FormRenderer {

  public static final String ANY = "*";

  @Override
  public String formCode() {
    return ANY;
  }

  @Override
  public Map<String, String> fields(Map<String, Object> data) {
    Map<String, String> out = new LinkedHashMap<>();
    flatten("", data, out);
    return out;
  }

  private static void flatten(String prefix, Object value, Map<String, String> out) {
    if (value instanceof Map<?, ?> map) {
      map.forEach((k, v) -> flatten(prefix.isEmpty() ? k.toString() : prefix + "." + k, v, out));
    } else if (value instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        flatten(prefix + "." + (i + 1), list.get(i), out);
      }
    } else {
      out.put(prefix, value == null ? "" : value.toString());
    }
  }
}
