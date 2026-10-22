package vn.viettel.okrkpi.appraisal.application.review.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 báo người được đánh giá hồ sơ đã bị trả lại để bổ sung (MSG-09-09). */
public interface ReviewNotices {

  void returned(TenantId tenant, UUID profileId, UUID personId, int returnCount, long version);
}
