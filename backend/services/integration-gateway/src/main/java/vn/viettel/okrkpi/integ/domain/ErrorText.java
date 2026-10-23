package vn.viettel.okrkpi.integ.domain;

/**
 * Rút gọn thông tin lỗi để ghi {@code dead_letter}: chỉ loại ngoại lệ và mã, không chép thông điệp
 * lỗi (có thể chứa nội dung thông điệp của hệ ngoài) vào CSDL hay nhật ký.
 */
public final class ErrorText {

  static final int MAX_CODE = 64;
  static final int MAX_DETAIL = 1000;

  private ErrorText() {}

  /** Mã lỗi: tên lớp ngoại lệ ngắn, tối đa 64 ký tự. */
  public static String code(Throwable error) {
    String name = error == null ? "KHONG_RO" : error.getClass().getSimpleName();
    if (name.isEmpty()) {
      name = "KHONG_RO";
    }
    return name.length() > MAX_CODE ? name.substring(0, MAX_CODE) : name;
  }

  /** Chi tiết: chuỗi nguyên nhân theo tên lớp, tối đa 1.000 ký tự. */
  public static String detail(Throwable error) {
    if (error == null) {
      return "Không rõ nguyên nhân";
    }
    StringBuilder sb = new StringBuilder();
    Throwable t = error;
    for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
      if (sb.length() > 0) {
        sb.append(" <- ");
      }
      sb.append(t.getClass().getName());
    }
    return sb.length() > MAX_DETAIL ? sb.substring(0, MAX_DETAIL) : sb.toString();
  }
}
