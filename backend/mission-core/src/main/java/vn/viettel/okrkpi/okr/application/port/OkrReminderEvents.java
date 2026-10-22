package vn.viettel.okrkpi.okr.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 của các nhắc việc OKR chạy nền (CORE-01-11); lõi không tự gửi thông báo. */
public interface OkrReminderEvents {

  /** Nhắc chủ sở hữu và người check-in thay: KR chưa check-in trong chu kỳ kết thúc {@code end}. */
  void checkinDue(TenantId tenant, DueKeyResult kr, List<UUID> recipients, LocalDate end);

  /** BR-03-08: KR quá hạn check-in hai chu kỳ liên tiếp, gửi người đứng đầu đơn vị. */
  void checkinOverdue(TenantId tenant, DueKeyResult kr, List<UUID> recipients, int cycles);
}
