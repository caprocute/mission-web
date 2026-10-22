package vn.viettel.okrkpi.appraisal.application.setup.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 báo cán bộ phiếu tự đánh giá đã mở (QT-09 bước 3). */
public interface SetupNotices {

  void selfAssessmentOpened(TenantId tenant, UUID profileId, UUID personId, long version);
}
