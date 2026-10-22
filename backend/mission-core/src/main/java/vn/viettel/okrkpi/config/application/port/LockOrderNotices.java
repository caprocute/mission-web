package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;

/** EVT-21 qua outbox của MOD-08 cho lệnh khoá kỳ (QT-08 bước 1, MSG-08-01). */
public interface LockOrderNotices {

  /** Gửi lãnh đạo (AC-04) lệnh chờ duyệt. */
  void requested(PeriodLockOrder order, String periodName, List<UUID> recipients);

  /** Báo người lập và quản trị tenant lệnh đã duyệt (MSG-08-01). */
  void approved(PeriodLockOrder order, String periodName, List<UUID> recipients);
}
