package vn.viettel.okrkpi.oversight.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của khiếu nại ghi outbox cùng giao dịch (EVT-25, EVT-21). */
public interface ComplaintEvents {

  /** Khiếu nại vừa gửi: EVT-25 cho SVC-06 và EVT-21 báo người khiếu nại (MSG-12-02). */
  void filed(TenantId tenant, Complaint c);

  /** Ký xong nhưng hồ sơ không còn khiếu nại được: EVT-21 báo người khiếu nại. */
  void notFiled(TenantId tenant, Complaint c);

  /**
   * Khiếu nại vừa đổi trạng thái (tiếp nhận, không thụ lý, rút): EVT-21 mẫu {@code template} gửi
   * {@code recipients}; không có người nhận thì không ghi.
   */
  void stateChanged(TenantId tenant, Complaint c, String template, Collection<UUID> recipients);

  /**
   * Như {@link #stateChanged(TenantId, Complaint, String, Collection)}, thêm tham số mẫu ngoài mã
   * khiếu nại (CORE-07-04, ví dụ hạn giải trình).
   */
  default void stateChanged(
      TenantId tenant,
      Complaint c,
      String template,
      Collection<UUID> recipients,
      Map<String, String> params) {
    stateChanged(tenant, c, template, recipients);
  }
}
