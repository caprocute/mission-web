package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Optional;

/**
 * Quy tắc cập nhật tiến độ của QT-06 (CORE-02-19): BR-06-03 chỉ người chủ trì đổi phần trăm, người
 * phối hợp chỉ ghi nhật ký; BR-06-02 giảm phần trăm phải có ghi chú (MSG-06-01); chỉ nhận cập nhật
 * khi nhiệm vụ đang {@code DA_GIAO} (lần đầu của chủ trì) hoặc {@code DANG_THUC_HIEN}.
 */
public final class ProgressRules {

  /** Vai trò của người gọi trên nhiệm vụ. */
  public enum Role {
    CHU_TRI,
    PHOI_HOP,
    KHAC
  }

  public enum Violation {
    NOT_ASSIGNEE,
    INVALID_STATE,
    COORDINATOR_PERCENT,
    DECREASE_WITHOUT_NOTE,
    INVALID_PERCENT
  }

  public static final int MAX_NOTE = 2000;

  private ProgressRules() {}

  public static Optional<Violation> check(
      TaskStatus status, Role role, int currentPercent, int requestedPercent, String note) {
    if (role == Role.KHAC) {
      return Optional.of(Violation.NOT_ASSIGNEE);
    }
    if (requestedPercent < 0
        || requestedPercent > 100
        || (note != null && note.length() > MAX_NOTE)) {
      return Optional.of(Violation.INVALID_PERCENT);
    }
    if (!accepts(status, role)) {
      return Optional.of(Violation.INVALID_STATE);
    }
    if (role == Role.PHOI_HOP && requestedPercent != currentPercent) {
      return Optional.of(Violation.COORDINATOR_PERCENT);
    }
    if (requestedPercent < currentPercent && (note == null || note.isBlank())) {
      return Optional.of(Violation.DECREASE_WITHOUT_NOTE);
    }
    return Optional.empty();
  }

  /**
   * Nhiệm vụ đang nhận cập nhật tiến độ của người có vai trò {@code role}: {@code DANG_THUC_HIEN},
   * hoặc {@code DA_GIAO} với người chủ trì (lần đầu).
   */
  public static boolean accepts(TaskStatus status, Role role) {
    return role != Role.KHAC
        && (status == TaskStatus.DANG_THUC_HIEN
            || (status == TaskStatus.DA_GIAO && role == Role.CHU_TRI));
  }

  /** Lần cập nhật này tự chuyển nhiệm vụ {@code DA_GIAO → DANG_THUC_HIEN} (lệnh ACCEPT, QT-05). */
  public static boolean startsWork(TaskStatus status, Role role) {
    return status == TaskStatus.DA_GIAO && role == Role.CHU_TRI;
  }
}
