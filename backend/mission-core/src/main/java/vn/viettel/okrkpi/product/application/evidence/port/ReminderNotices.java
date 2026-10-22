package vn.viettel.okrkpi.product.application.evidence.port;

import java.time.LocalDate;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 nhắc quá hạn nghiệm thu (BR-07-12, MSG-07-14), ghi cùng giao dịch với lần giữ chỗ. */
public interface ReminderNotices {

  /** Nhắc người nghiệm thu: {@code count} minh chứng chờ quá {@code days} ngày làm việc. */
  void overdueForReviewer(TenantId tenant, UUID reviewerId, int count, int days, LocalDate day);

  /** Bản tóm tắt cho cấp trên: số minh chứng quá hạn và số người nghiệm thu dưới quyền. */
  void overdueSummary(
      TenantId tenant, UUID supervisorId, int count, int reviewers, int days, LocalDate day);
}
