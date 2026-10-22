package vn.viettel.okrkpi.config.application.port;

import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodTransition;

/**
 * Ghi sự kiện chuyển trạng thái kỳ vào {@code config.outbox_event} trong giao dịch đang mở: chốt kỳ
 * và khoá lại phát EVT-09 {@code config.period.locked.v1}, bù trừ về {@code MO} phát EVT-26 {@code
 * config.period.unlocked.v1}.
 */
public interface PeriodEvents {

  /**
   * @param reason lý do huỷ khoá (chỉ dùng cho EVT-26), có thể trống
   * @param lockOrderId lệnh khoá kỳ liên quan (chỉ dùng cho EVT-26), có thể trống
   */
  void statusChanged(
      Period period, PeriodTransition transition, java.util.UUID lockOrderId, String reason);
}
