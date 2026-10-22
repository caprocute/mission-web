package vn.viettel.okrkpi.okr.infrastructure.persistence;

/**
 * Bỏ dấu tiếng Việt để tìm kiếm "không phân biệt dấu" ({@code common.yaml} tham số {@code q}): cùng
 * một bảng ký tự dùng cho {@code translate()} của PostgreSQL và cho từ khoá ở Java.
 */
final class VietnameseFold {

  static final String FROM = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ";
  static final String TO = "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd";

  private VietnameseFold() {}

  static String fold(String text) {
    String lower = text.toLowerCase(java.util.Locale.ROOT);
    StringBuilder b = new StringBuilder(lower.length());
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      int at = FROM.indexOf(c);
      b.append(at >= 0 ? TO.charAt(at) : c);
    }
    return b.toString();
  }
}
