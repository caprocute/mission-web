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
}
