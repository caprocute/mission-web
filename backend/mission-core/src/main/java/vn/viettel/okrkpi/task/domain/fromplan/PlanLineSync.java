package vn.viettel.okrkpi.task.domain.fromplan;

import java.util.Set;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/**
 * Việc cần làm với nhiệm vụ của một dòng kế hoạch khi kế hoạch được duyệt (QT-04 bước 8, QT-05 bước
 * 1a, BR-04-11). Dòng sửa chỉ cập nhật nội dung nhiệm vụ chưa bắt đầu ({@code NHAP}, hoặc {@code
 * DA_GIAO} mà người chủ trì chưa nhận, suy luận); dòng bỏ thì xoá bản nháp hoặc huỷ qua máy trạng
 * thái khi chưa có minh chứng được xác nhận.
 */
public final class PlanLineSync {

  /** Việc với nhiệm vụ đã gắn dòng gốc khi dòng được sửa. */
  public enum Update {
    /** Ghi lại toàn bộ bản nháp, kể cả người chủ trì và phối hợp. */
    REWRITE_DRAFT,
    /** Cập nhật nội dung, giữ người được giao. */
    REFRESH_ASSIGNED,
    /** Nhiệm vụ đang làm hoặc đã xong: chỉ gắn sang dòng mới, giữ nguyên nội dung và hạn. */
    RELINK
  }

  /** Việc với nhiệm vụ của dòng bị bỏ. */
  public enum Removal {
    DISCARD_DRAFT,
    CANCEL,
    /** Không huỷ được: có minh chứng đã xác nhận, đang chờ nghiệm thu hoặc đã hoàn thành. */
    BLOCKED,
    NONE
  }

  /** Trạng thái huỷ được qua lệnh {@code CANCEL} của bảng chuyển mặc định. */
  static final Set<TaskStatus> CANCELLABLE =
      Set.of(TaskStatus.DA_GIAO, TaskStatus.DANG_THUC_HIEN, TaskStatus.TAM_DUNG);

  private PlanLineSync() {}

  public static Update update(TaskStatus status) {
    return switch (status) {
      case NHAP -> Update.REWRITE_DRAFT;
      case DA_GIAO -> Update.REFRESH_ASSIGNED;
      default -> Update.RELINK;
    };
  }

  /**
   * @param cancelAllowed bảng chuyển đang áp có lệnh {@code CANCEL} từ trạng thái này
   * @param confirmedEvidence nhiệm vụ có minh chứng {@code DA_XAC_NHAN}
   */
  public static Removal removal(
      TaskStatus status, boolean cancelAllowed, boolean confirmedEvidence) {
    if (status == TaskStatus.HUY) {
      return Removal.NONE;
    }
    if (status == TaskStatus.NHAP) {
      return Removal.DISCARD_DRAFT;
    }
    if (cancelAllowed && !confirmedEvidence) {
      return Removal.CANCEL;
    }
    return Removal.BLOCKED;
  }

  /** Trạng thái có thể có lệnh huỷ (để chỉ đọc minh chứng khi cần). */
  public static boolean mayCancel(TaskStatus status) {
    return CANCELLABLE.contains(status);
  }
}
