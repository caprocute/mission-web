package vn.viettel.okrkpi.task.domain.catalog;

import java.util.Locale;

/**
 * Bỏ dấu tiếng Việt và chữ hoa để tìm kiếm "không phân biệt dấu" ({@code common.yaml} tham số
 * {@code q}). Cùng một bảng ký tự dùng cho {@code translate()} của PostgreSQL và cho so khớp trong
 * bộ nhớ, nên hai phía cho cùng kết quả.
 */
public final class AccentFold {

  private static final String LOWER_FROM =
      "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ";
  private static final String LOWER_TO =
      "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd";

  /** Ký tự nguồn của {@code translate(lower(x), FROM, TO)}, gồm cả chữ hoa có dấu. */
  public static final String FROM = LOWER_FROM + LOWER_FROM.toUpperCase(Locale.ROOT);

  /** Ký tự đích tương ứng {@link #FROM}. */
  public static final String TO = LOWER_TO + LOWER_TO;

  private AccentFold() {}

  /** Chuỗi đã bỏ dấu, chữ thường; {@code null} giữ {@code null}. */
  public static String fold(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      int at = FROM.indexOf(c);
      out.append(at >= 0 ? TO.charAt(at) : Character.toLowerCase(c));
    }
    return out.toString().toLowerCase(Locale.ROOT);
  }
}
