package vn.viettel.okrkpi.oversight.application.port;

import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của khiếu nại ghi outbox cùng giao dịch (EVT-25, EVT-21). */
public interface ComplaintEvents {

  /** Khiếu nại vừa gửi: EVT-25 cho SVC-06 và EVT-21 báo người khiếu nại (MSG-12-02). */
  void filed(TenantId tenant, Complaint c);

  /** Ký xong nhưng hồ sơ không còn khiếu nại được: EVT-21 báo người khiếu nại. */
  void notFiled(TenantId tenant, Complaint c);
}
