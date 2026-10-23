package vn.viettel.okrkpi.oversight.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 của các bước xử lý khiếu nại, ghi outbox cùng giao dịch chuyển trạng thái. */
public interface ComplaintNotices {

  /** Mẫu thông báo (suy luận, wiki trang 14); tham số luôn có {@code ma} là mã khiếu nại. */
  String ACCEPTED = "KHIEU_NAI_DA_TIEP_NHAN";

  String REJECTED = "KHIEU_NAI_KHONG_THU_LY";
  String WITHDRAWN = "KHIEU_NAI_DA_RUT";
  String EXPLANATION_REQUESTED = "KHIEU_NAI_YEU_CAU_GIAI_TRINH";
  String RESPONDED = "KHIEU_NAI_DA_TRA_LOI";

  void notify(
      TenantId tenant,
      Complaint c,
      Collection<UUID> recipients,
      String template,
      Map<String, String> params);
}
