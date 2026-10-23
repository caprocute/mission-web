package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông báo EVT-21 của yêu cầu mở lại kỳ (QT-13, MSG-13-03). */
public interface ReopenNotices {

  /** Người được chỉ định cho lượt duyệt {@code round} có việc cần duyệt. */
  void approvalRequested(TenantId tenant, UUID assignee, ReopenRequest request, int round);

  /** Báo người lập: yêu cầu đã đủ hai phê duyệt ({@code DA_DUYET}) hoặc bị từ chối. */
  void decided(TenantId tenant, ReopenRequest request);

  /**
   * Hết hạn mở mà còn hồ sơ chưa tính lại, sinh lại, ký lại xong: báo người lập "chưa hoàn tất hậu
   * kiểm" (ngoại lệ QT-13, CORE-07-09).
   */
  default void followUpIncomplete(TenantId tenant, ReopenRequest request, int pendingProfiles) {}

  /** Kỳ đã khoá lại và yêu cầu đã đóng: báo người nhận (người lập, người xử lý căn cứ). */
  default void followUpClosed(TenantId tenant, ReopenRequest request, UUID recipient) {}
}
