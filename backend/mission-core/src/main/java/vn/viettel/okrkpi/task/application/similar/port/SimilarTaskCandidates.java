package vn.viettel.okrkpi.task.application.similar.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nguồn ứng viên gần giống ngoài CSDL nhiệm vụ: tìm kiếm toàn văn SVC-16. */
public interface SimilarTaskCandidates {

  /**
   * Mã nhiệm vụ ứng viên theo tên, cùng kỳ và đơn vị; {@code authorization} là header của người gọi
   * để nguồn ứng viên kiểm phạm vi.
   */
  List<UUID> candidates(
      TenantId tenant, UUID periodId, UUID unitId, String title, int limit, String authorization);

  /** Nguồn ứng viên lỗi, quá thời gian hoặc không gọi được. */
  class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
