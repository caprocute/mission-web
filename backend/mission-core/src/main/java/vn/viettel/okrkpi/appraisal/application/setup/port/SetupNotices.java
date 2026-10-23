package vn.viettel.okrkpi.appraisal.application.setup.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 báo cán bộ phiếu tự đánh giá đã mở (QT-09 bước 3). */
public interface SetupNotices {

  void selfAssessmentOpened(TenantId tenant, UUID profileId, UUID personId, long version);

  /**
   * EVT-21 MSG-10-04: kết quả năm thay đổi sau hậu kiểm quý, hồ sơ năm về bước quyết định
   * (CORE-04-10).
   */
  default void annualRatingChanged(
      TenantId tenant,
      UUID profileId,
      List<UUID> recipients,
      RatingLevel before,
      RatingLevel after,
      long version) {}
}
