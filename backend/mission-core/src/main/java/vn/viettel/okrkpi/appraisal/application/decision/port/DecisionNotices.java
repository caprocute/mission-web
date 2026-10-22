package vn.viettel.okrkpi.appraisal.application.decision.port;

import java.time.LocalDate;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của bước quyết định ghi outbox cùng giao dịch: EVT-12 và EVT-21. */
public interface DecisionNotices {

  /**
   * EVT-12 {@code appraisal.rating.decided.v1} (không chứa C2) và EVT-21 báo cán bộ kết quả kèm hạn
   * khiếu nại (MSG-09-11).
   */
  void decided(
      TenantId tenant,
      ProfileRow row,
      RatingLevel level,
      UUID deciderId,
      UUID signRequestId,
      LocalDate complaintDeadline,
      long version);

  /** EVT-21 báo người quyết định lô đã ký xong (MSG-09-16). */
  void batchCompleted(
      TenantId tenant, UUID batchId, UUID deciderId, String decisionNo, int total, long version);
}
