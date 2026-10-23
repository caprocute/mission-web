package vn.viettel.okrkpi.config.api;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khoá lại kỳ mở lại hậu kiểm trước hạn khi MOD-07 báo đã tính lại, sinh lại, ký lại xong mọi hồ sơ
 * trong phạm vi (QT-13 bước 12, CORE-07-09). Kỳ về {@code DA_KHOA} và EVT-09 có {@code sagaId} là
 * mã yêu cầu mở lại.
 */
public interface PeriodReopenApi {

  /**
   * @return {@code false} khi lần mở lại đã bắt đầu khoá lại từ trước hoặc MOD-08 chưa nhận EVT-22
   *     của yêu cầu này
   */
  boolean finishEarly(TenantId tenantId, UUID reopenRequestId, UUID actorId);
}
