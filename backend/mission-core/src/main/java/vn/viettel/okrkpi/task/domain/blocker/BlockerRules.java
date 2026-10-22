package vn.viettel.okrkpi.task.domain.blocker;

import java.util.Map;
import java.util.Set;

/**
 * Máy trạng thái nhỏ của điểm nghẽn ({@code task.task_blocker.status}). TL-02a chỉ mô tả "mở" và
 * "đóng", nên bảng chuyển dưới đây là suy luận: {@code MO → DANG_XU_LY → DA_GIAI_QUYET → DONG};
 * người giao được đóng khi còn {@code MO} hoặc {@code DANG_XU_LY} nếu ghi lý do. Đã {@code DONG} là
 * bất biến.
 */
public final class BlockerRules {

  public static final Set<String> STATUSES = Set.of("MO", "DANG_XU_LY", "DA_GIAI_QUYET", "DONG");
  public static final Set<String> OPEN = Set.of("MO", "DANG_XU_LY");
  public static final Set<String> CAUSES =
      Set.of("NGUON_LUC", "PHOI_HOP", "THU_TUC", "KHACH_QUAN", "KHAC");
  public static final Set<String> IMPACTS = Set.of("CAO", "TRUNG_BINH", "THAP");
  public static final Set<String> CLAIMS = Set.of("KHACH_QUAN", "CHU_QUAN");

  private static final Map<String, Set<String>> NEXT =
      Map.of(
          "MO", Set.of("DANG_XU_LY", "DONG"),
          "DANG_XU_LY", Set.of("DA_GIAI_QUYET", "DONG"),
          "DA_GIAI_QUYET", Set.of("DONG"),
          "DONG", Set.of());

  private BlockerRules() {}

  /** Lý do một thay đổi bị từ chối. */
  public enum Violation {
    /** Chuyển trạng thái không có trong bảng (409). */
    INVALID_TRANSITION,
    /** Giải quyết hoặc đóng khi chưa xong mà thiếu ghi chú kết quả (422). */
    NOTE_REQUIRED,
    /** Chuyển sang đang xử lý mà chưa có người xử lý (422). */
    HANDLER_REQUIRED
  }

  /**
   * Kiểm một lần xử lý; {@code to} rỗng là giữ trạng thái. Trả {@code null} khi hợp lệ.
   *
   * @param handler người xử lý sau khi áp thay đổi
   * @param note ghi chú kết quả gửi kèm
   */
  public static Violation check(String from, String to, Object handler, String note) {
    if (to == null || to.equals(from)) {
      return "DONG".equals(from) ? Violation.INVALID_TRANSITION : null;
    }
    if (!NEXT.getOrDefault(from, Set.of()).contains(to)) {
      return Violation.INVALID_TRANSITION;
    }
    if ("DANG_XU_LY".equals(to) && handler == null) {
      return Violation.HANDLER_REQUIRED;
    }
    boolean blank = note == null || note.isBlank();
    if ("DA_GIAI_QUYET".equals(to) && blank) {
      return Violation.NOTE_REQUIRED;
    }
    if ("DONG".equals(to) && OPEN.contains(from) && blank) {
      return Violation.NOTE_REQUIRED;
    }
    return null;
  }

  /**
   * Trạng thái sau khi giao người xử lý: điểm nghẽn còn {@code MO} mà có người xử lý thì sang
   * {@code DANG_XU_LY}.
   */
  public static String afterHandler(String status, Object handler) {
    return "MO".equals(status) && handler != null ? "DANG_XU_LY" : status;
  }

  /** Mã hiển thị {@code BN-<số thứ tự nhiệm vụ>-<số thứ tự điểm nghẽn hai chữ số>}. */
  public static String code(String taskCode, int seq) {
    String tail = taskCode == null ? "" : taskCode.substring(taskCode.lastIndexOf('-') + 1);
    return "BN-" + tail + "-" + String.format("%02d", seq);
  }
}
