package vn.viettel.okrkpi.oversight.domain.complaint;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ràng buộc của khiếu nại theo SCR-1201 bảng trường và BR-12-02, BR-12-05. */
public final class ComplaintRules {

  public static final int CONTENT_MIN = 50;
  public static final int CONTENT_MAX = 8000;
  public static final int REQUEST_MAX = 500;
  public static final int SUBJECT_MAX = 255;
  public static final int TARGET_KEY_MAX = 100;
  public static final int TARGET_LABEL_MAX = 200;

  /** Số tệp kèm tối đa của một khiếu nại (suy luận; kích thước, quét mã độc do SVC-08 kiểm). */
  public static final int ATTACHMENTS_MAX = 20;

  /** Hạn trả lời bằng văn bản: 15 ngày làm việc kể từ khi gửi (BR-12-05). */
  public static final int RESPONSE_WORKING_DAYS = 15;

  private static final Pattern CODE = Pattern.compile("^KN-(\\d{4})-(\\d{4,})$");

  private ComplaintRules() {}

  /** Mã {@code KN-yyyy-nnnn}. */
  public static String code(int year, int sequence) {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence");
    }
    return String.format("KN-%04d-%04d", year, sequence);
  }

  /** Số thứ tự của mã trong năm; mã sai mẫu hoặc khác năm trả 0. */
  public static int sequenceOf(String code, int year) {
    if (code == null) {
      return 0;
    }
    Matcher m = CODE.matcher(code);
    if (!m.matches() || Integer.parseInt(m.group(1)) != year) {
      return 0;
    }
    return Integer.parseInt(m.group(2));
  }

  /** Số ký tự (điểm mã) sau khi bỏ khoảng trắng hai đầu; {@code null} là 0. */
  public static int length(String s) {
    if (s == null) {
      return 0;
    }
    String t = s.strip();
    return t.codePointCount(0, t.length());
  }
}
