package vn.viettel.okrkpi.okr.application.port;

import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện của lệnh gửi check-in (CORE-01-09), ghi outbox {@code okr.outbox_event} trong giao dịch
 * của bên gọi: EVT-02 cho mọi check-in {@code DA_GUI}, EVT-21 khi KR vừa chuyển {@code RUI_RO}.
 */
public interface OkrCheckinEvents {

  /** EVT-02 {@code okr.keyresult.checkedin.v1}, khoá {@code tenantId:keyResultId}. */
  void checkedIn(TenantId tenant, CheckinTarget keyResult, CheckinRow checkin, long rowVersion);

  /** EVT-21 tới lãnh đạo đơn vị sở hữu, nội dung theo MSG-02-01. */
  void atRisk(TenantId tenant, CheckinTarget keyResult, CheckinRow checkin, long rowVersion);
}
