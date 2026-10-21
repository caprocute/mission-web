package vn.viettel.okrkpi.org.infrastructure.web;

/** Đọc {@code If-Match} dạng {@code "7"}, {@code W/"7"} hoặc {@code 7} thành {@code row_version}. */
final class IfMatch {

  private IfMatch() {
  }

  static int parse(String header) {
    String value = header.trim();
    if (value.startsWith("W/")) {
      value = value.substring(2);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new MalformedIfMatchException();
    }
  }

  /** {@code If-Match} không phải ETag do máy chủ cấp: 400. */
  static class MalformedIfMatchException extends RuntimeException {
    MalformedIfMatchException() {
      super("Giá trị If-Match không hợp lệ.");
    }
  }
}
