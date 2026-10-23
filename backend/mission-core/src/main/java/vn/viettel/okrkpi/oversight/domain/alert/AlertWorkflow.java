package vn.viettel.okrkpi.oversight.domain.alert;

import java.util.List;
import java.util.Set;

/**
 * Chuyển trạng thái cảnh báo khi người xử lý ra hành động (CORE-07-11, QT-14 "Trạng thái đối
 * tượng"): {@code XAC_NHAN_HOP_LE} từ {@code DANG_XEM} qua {@code HOP_LE} tới {@code DONG}; {@code
 * GIAO_KIEM_TRA} từ {@code DANG_XEM}; {@code KET_LUAN} từ {@code DA_GIAO_KIEM_TRA}, không vi phạm
 * thì đóng ngay, có vi phạm thì chỉ đóng khi kỳ còn mở hoặc đã có yêu cầu mở lại kỳ lấy cảnh báo
 * làm căn cứ; {@code GHI_CHU} không đổi trạng thái.
 */
public final class AlertWorkflow {

  public static final String VALIDATE = "XAC_NHAN_HOP_LE";
  public static final String ASSIGN = "GIAO_KIEM_TRA";
  public static final String CONCLUDE = "KET_LUAN";
  public static final String NOTE = "GHI_CHU";
  public static final Set<String> TYPES = Set.of(VALIDATE, ASSIGN, CONCLUDE, NOTE);

  public static final String VIEWING = "DANG_XEM";
  public static final String VALID = "HOP_LE";
  public static final String ASSIGNED = "DA_GIAO_KIEM_TRA";
  public static final String VIOLATION = "CO_VI_PHAM";
  public static final String NO_VIOLATION = "KHONG_VI_PHAM";
  public static final Set<String> VERDICTS = Set.of(VIOLATION, NO_VIOLATION);

  public static final int NOTE_MIN = 20;
  public static final int NOTE_MAX = 2000;

  private AlertWorkflow() {}

  /** Trạng thái đi qua theo thứ tự (rỗng khi không đổi). */
  public record Decision(List<String> statuses, boolean unblock) {
    public Decision {
      statuses = List.copyOf(statuses);
    }

    public String last(String current) {
      return statuses.isEmpty() ? current : statuses.get(statuses.size() - 1);
    }
  }

  /** Hành động không hợp với trạng thái hiện tại. */
  public static final class InvalidState extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidState(String message) {
      super(message);
    }
  }

  /** Ghi chú xử lý tính theo ký tự (code point), bỏ khoảng trắng hai đầu. */
  public static boolean noteValid(String note) {
    if (note == null) {
      return false;
    }
    int n = note.strip().codePointCount(0, note.strip().length());
    return n >= NOTE_MIN && n <= NOTE_MAX;
  }

  /**
   * @param closable với kết luận có vi phạm: kỳ còn mở, hoặc đã có yêu cầu mở lại kỳ lấy cảnh báo
   *     làm căn cứ
   */
  public static Decision decide(String status, String type, String verdict, boolean closable) {
    return switch (type) {
      case VALIDATE -> {
        require(status, VIEWING, "Chỉ xác nhận hợp lệ cảnh báo đang được xem xét.");
        yield new Decision(List.of(VALID, AlertRules.DONG), true);
      }
      case ASSIGN -> {
        require(status, VIEWING, "Chỉ giao kiểm tra cảnh báo đang được xem xét.");
        yield new Decision(List.of(ASSIGNED), false);
      }
      case CONCLUDE -> {
        require(status, ASSIGNED, "Chỉ kết luận cảnh báo đã giao kiểm tra.");
        if (NO_VIOLATION.equals(verdict)) {
          yield new Decision(List.of(NO_VIOLATION, AlertRules.DONG), true);
        }
        yield new Decision(
            closable ? List.of(VIOLATION, AlertRules.DONG) : List.of(VIOLATION), false);
      }
      case NOTE -> {
        if (AlertRules.DONG.equals(status)) {
          throw new InvalidState("Cảnh báo đã đóng.");
        }
        yield new Decision(List.of(), false);
      }
      default -> throw new IllegalArgumentException(type);
    };
  }

  /** Thứ tự hiển thị theo mức: Cao 0, Vừa 1, Thấp 2 (khớp {@code CASE} của kho). */
  public static int order(String severity) {
    return 3 - AlertRules.rank(severity);
  }

  /** Cảnh báo có vi phạm chờ yêu cầu mở lại kỳ thì đóng khi yêu cầu được lập. */
  public static boolean awaitsReopen(String status) {
    return VIOLATION.equals(status);
  }

  private static void require(String status, String expected, String message) {
    if (!expected.equals(status)) {
      throw new InvalidState(message);
    }
  }
}
