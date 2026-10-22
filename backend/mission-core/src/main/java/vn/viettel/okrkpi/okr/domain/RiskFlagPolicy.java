package vn.viettel.okrkpi.okr.domain;

import java.util.List;

/**
 * Máy trạng thái cờ rủi ro của KR (TL-02a QT-03 "Trạng thái đối tượng", BR-03-06). Chỉ tính
 * check-in {@code DA_GUI} theo thứ tự {@code checkin_at}:
 *
 * <ul>
 *   <li>{@code BINH_THUONG} → {@code THEO_DOI} khi một lần tự tin Thấp (không thông báo);
 *   <li>{@code THEO_DOI} → {@code RUI_RO} khi Thấp lần thứ hai liên tiếp; Trung bình hoặc Cao thì
 *       về {@code BINH_THUONG};
 *   <li>{@code RUI_RO} → {@code BINH_THUONG} sau hai lần liên tiếp không Thấp, để cờ không bật tắt
 *       liên tục.
 * </ul>
 *
 * Lớp thuần, không phụ thuộc Spring.
 */
public final class RiskFlagPolicy {

  public static final String BINH_THUONG = "BINH_THUONG";
  public static final String THEO_DOI = "THEO_DOI";
  public static final String RUI_RO = "RUI_RO";
  public static final String THAP = "THAP";

  private RiskFlagPolicy() {}

  /**
   * Cờ sau một lần gửi.
   *
   * @param current cờ hiện tại của KR (rỗng coi là {@code BINH_THUONG})
   * @param previousConfidence mức tự tin của lần gửi ngay trước, rỗng nếu chưa có
   * @param confidence mức tự tin của lần gửi này
   */
  public static String next(String current, String previousConfidence, String confidence) {
    boolean low = THAP.equals(confidence);
    String flag = current == null ? BINH_THUONG : current;
    return switch (flag) {
      case THEO_DOI -> low ? RUI_RO : BINH_THUONG;
      case RUI_RO ->
          !low && previousConfidence != null && !THAP.equals(previousConfidence)
              ? BINH_THUONG
              : RUI_RO;
      default -> low ? THEO_DOI : BINH_THUONG;
    };
  }

  /** Cờ sau một chuỗi lần gửi, bắt đầu từ {@code BINH_THUONG}. */
  public static String replay(List<String> confidences) {
    String flag = BINH_THUONG;
    String previous = null;
    for (String c : confidences) {
      flag = next(flag, previous, c);
      previous = c;
    }
    return flag;
  }

  /** Lần gửi này vừa đưa KR vào {@code RUI_RO} (cần thông báo lãnh đạo, EVT-21). */
  public static boolean becameAtRisk(String before, String after) {
    return RUI_RO.equals(after) && !RUI_RO.equals(before);
  }
}
