package vn.viettel.okrkpi.task.application.assign.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-21 của đăng ký nhiệm vụ (CORE-02-17): tới người quản lý, người đăng ký, người lập kế hoạch.
 */
public interface RegistrationNotices {

  /** Cán bộ vừa đăng ký: báo người quản lý trực tiếp xem xét. */
  void submitted(TenantId tenant, UUID taskId, String code, List<UUID> managers);

  /** Đăng ký bị từ chối: báo người đăng ký. */
  void rejected(TenantId tenant, UUID taskId, String code, UUID registrant);

  /** Đăng ký đã duyệt nhưng chưa có phiên bản điều chỉnh đang soạn (MSG-05-12). */
  void waitingForAdjustment(TenantId tenant, UUID taskId, String code, List<UUID> planners);
}
