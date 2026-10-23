package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đề nghị MOD-08 khoá lại kỳ trước hạn khi hậu kiểm hoàn tất (QT-13 bước 12). */
@FunctionalInterface
public interface PeriodRelock {

  /**
   * @return {@code false} khi lần mở lại đã bắt đầu khoá lại từ trước hoặc MOD-08 không biết lần mở
   *     lại này
   */
  boolean finishEarly(TenantId tenant, UUID reopenRequestId, UUID actorId);
}
