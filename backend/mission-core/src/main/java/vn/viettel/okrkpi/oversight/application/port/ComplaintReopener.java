package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tạo yêu cầu mở lại kỳ thi hành kết luận có điều chỉnh trên kỳ đã khoá (BR-12-07, MSG-12-07), qua
 * cổng nội bộ {@code ReopenRequestService.createInternal}; chạy trong giao dịch của kết luận.
 *
 * @return mã yêu cầu mở lại
 */
@FunctionalInterface
public interface ComplaintReopener {

  UUID open(TenantId tenant, UUID requesterId, Complaint complaint, ComplaintConclusion conclusion);
}
