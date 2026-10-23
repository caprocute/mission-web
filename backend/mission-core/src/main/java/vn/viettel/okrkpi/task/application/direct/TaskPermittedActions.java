package vn.viettel.okrkpi.task.application.direct;

import java.util.LinkedHashSet;
import java.util.Set;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules.Role;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/**
 * Thao tác người gọi được làm trên một nhiệm vụ ({@code TaskSummary.permittedActions}, YC-PT-21),
 * dạng khoá chức năng {@code SCR-qqnn.<khoá>} của ma trận phân quyền. Điều kiện bám đúng điều kiện
 * mà dịch vụ nghiệp vụ kiểm khi thực hiện thao tác; đây chỉ là gợi ý hiển thị, thao tác vẫn được
 * kiểm lại khi gọi.
 */
public final class TaskPermittedActions {

  /** Nhắc người chủ trì ({@code remindTask}). */
  public static final String NHAC = "SCR-1601.nhac";

  /** Giao lại ({@code reassignTask}). */
  public static final String GIAO_LAI = "SCR-1601.giao-lai";

  /** Nhận việc ({@code acceptTask}). */
  public static final String NHAN_VIEC = "SCR-0503.nhan-viec";

  /** Cập nhật tiến độ từ danh sách "Việc của tôi" ({@code updateTaskProgress}). */
  public static final String TIEN_DO = "SCR-0503.tien-do";

  /** Cập nhật tiến độ trên trang chi tiết nhiệm vụ ({@code updateTaskProgress}). */
  public static final String TIEN_DO_CHI_TIET = "SCR-0601.tien-do";

  private TaskPermittedActions() {}

  /**
   * Thao tác của người tham gia trên danh sách "Việc của tôi": nhận việc chỉ người chủ trì khi
   * {@code DA_GIAO} ({@code acceptTask}), cập nhật tiến độ theo {@link ProgressRules#accepts}.
   *
   * @param role vai trò của người gọi trên nhiệm vụ ({@code CHU_TRI}, {@code PHOI_HOP}) hoặc {@code
   *     null}
   */
  public static Set<String> mine(TaskStatus status, String role) {
    Set<String> out = new LinkedHashSet<>();
    if ("CHU_TRI".equals(role) && status == TaskStatus.DA_GIAO) {
      out.add(NHAN_VIEC);
    }
    if (ProgressRules.accepts(status, role(role))) {
      out.add(TIEN_DO);
    }
    return out;
  }

  /** Cập nhật tiến độ trên trang chi tiết, cùng điều kiện với {@link #mine}. */
  public static Set<String> detail(TaskStatus status, String role) {
    return ProgressRules.accepts(status, role(role)) ? Set.of(TIEN_DO_CHI_TIET) : Set.of();
  }

  static Role role(String role) {
    if ("CHU_TRI".equals(role)) {
      return Role.CHU_TRI;
    }
    return "PHOI_HOP".equals(role) ? Role.PHOI_HOP : Role.KHAC;
  }
}
