package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;

/**
 * EVT-21 qua outbox của MOD-08 cho lệnh khoá kỳ (QT-08 bước 1, MSG-08-01) và cho huỷ, bù trừ (QT-08
 * bước 9, MSG-08-05, MSG-08-08). Hai hàm huỷ có thân mặc định rỗng để bản kiểm thử cũ không phải
 * hiện thực.
 */
public interface LockOrderNotices {

  /** Gửi lãnh đạo (AC-04) lệnh chờ duyệt. */
  void requested(PeriodLockOrder order, String periodName, List<UUID> recipients);

  /** Báo người lập và quản trị tenant lệnh đã duyệt (MSG-08-01). */
  void approved(PeriodLockOrder order, String periodName, List<UUID> recipients);

  /** Gửi người duyệt thứ hai (AC-09) đề nghị huỷ và bù trừ đang chờ (MSG-08-05). */
  default void cancelRequested(PeriodLockOrder order, String periodName, List<UUID> recipients) {}

  /** Báo người đề nghị và quản trị tenant saga đã bù trừ toàn bộ, kỳ trở về mở (MSG-08-08). */
  default void cancelCompleted(PeriodLockOrder order, String periodName, List<UUID> recipients) {}
}
