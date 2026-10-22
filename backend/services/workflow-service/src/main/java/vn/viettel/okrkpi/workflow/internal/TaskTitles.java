package vn.viettel.okrkpi.workflow.internal;

import java.util.regex.Pattern;

/**
 * Chuẩn hoá tiêu đề việc trước khi ghi bảng chiếu: gộp khoảng trắng, bỏ ký tự điều khiển, cắt về
 * 255 ký tự của cột {@code title}. Chỉ dùng trong mô-đun (TL-01 "internalIsPrivateToModule").
 */
public final class TaskTitles {

  /** Tiêu đề thay thế khi user task không có tên. */
  public static final String FALLBACK = "Việc cần xử lý";

  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private TaskTitles() {}

  /** Tiêu đề đã chuẩn hoá, không rỗng, tối đa {@code maxLength} ký tự. */
  public static String normalize(String raw, int maxLength) {
    if (raw == null) {
      return FALLBACK;
    }
    String cleaned = SPACES.matcher(CONTROL.matcher(raw).replaceAll(" ")).replaceAll(" ").trim();
    if (cleaned.isEmpty()) {
      return FALLBACK;
    }
    if (cleaned.length() <= maxLength) {
      return cleaned;
    }
    return cleaned.substring(0, maxLength - 1).trim() + "…";
  }
}
