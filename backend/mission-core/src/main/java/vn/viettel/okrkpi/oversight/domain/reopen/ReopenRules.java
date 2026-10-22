package vn.viettel.okrkpi.oversight.domain.reopen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ràng buộc của yêu cầu mở lại kỳ theo SCR-1301 và mục "Ngoại lệ và xử lý" QT-13. */
public final class ReopenRules {

  public static final int REASON_MIN = 50;
  public static final int REASON_MAX = 4000;
  public static final int OPEN_DAYS_MIN = 1;
  public static final int OPEN_DAYS_MAX = 10;
  public static final int CANCEL_REASON_MIN = 10;
  public static final int CANCEL_REASON_MAX = 2000;
  public static final int APPROVALS_REQUIRED = 2;

  private static final Pattern CODE = Pattern.compile("^MLK-(\\d{4})-(\\d{3,})$");

  private ReopenRules() {}

  /** Mã {@code MLK-yyyy-nnn}, số thứ tự ít nhất ba chữ số. */
  public static String code(int year, int sequence) {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence");
    }
    return String.format("MLK-%04d-%03d", year, sequence);
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

  public static boolean reasonValid(String reason) {
    return lengthBetween(reason, REASON_MIN, REASON_MAX);
  }

  public static boolean cancelReasonValid(String reason) {
    return lengthBetween(reason, CANCEL_REASON_MIN, CANCEL_REASON_MAX);
  }

  public static boolean openDaysValid(int days) {
    return days >= OPEN_DAYS_MIN && days <= OPEN_DAYS_MAX;
  }

  /**
   * Phạm vi toàn kỳ chỉ khi có quyết định hậu kiểm toàn diện (BR-13-05, SCR-1301 trường 4): căn cứ
   * loại {@code QUYET_DINH} có số quyết định (suy luận).
   */
  public static boolean wholePeriodAllowed(ReopenBasis basis) {
    return basis != null && basis.kind() == ReopenBasis.Kind.QUYET_DINH && basis.code() != null;
  }

  private static boolean lengthBetween(String s, int min, int max) {
    if (s == null) {
      return false;
    }
    int n = s.strip().codePointCount(0, s.strip().length());
    return n >= min && n <= max;
  }
}
