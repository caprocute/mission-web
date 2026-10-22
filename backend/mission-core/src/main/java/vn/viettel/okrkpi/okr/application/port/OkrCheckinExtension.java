package vn.viettel.okrkpi.okr.application.port;

import java.math.BigDecimal;
import java.util.UUID;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điểm mở rộng của lệnh gửi check-in (CORE-01-08): chạy trong cùng giao dịch, sau khi đã ghi
 * check-in và tính lại tiến độ, khi các dòng mục tiêu và KR vẫn đang bị khoá. CORE-01-09 (cờ rủi
 * ro, EVT-02, EVT-21) và CORE-01-10 (số liệu nguồn) cắm vào đây.
 */
public interface OkrCheckinExtension {

  /** Bối cảnh một lần gửi: KR trước khi gửi, check-in vừa ghi, tiến độ mới. */
  record Submitted(
      TenantId tenant,
      UUID actor,
      CheckinTarget before,
      CheckinRow checkin,
      BigDecimal keyResultProgress,
      BigDecimal objectiveProgress,
      boolean onBehalf) {}

  void onSubmitted(Submitted submitted);
}
