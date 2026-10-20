package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Tra kỳ đánh giá đã khoá chứa một ngày (để hiện MSG-01-10). Kỳ thuộc MOD-08 ({@code config.period}); hiện thực tạm
 * đọc cấu hình, hiện thực đích nhận EVT-09/EVT-26 (điểm chờ).
 */
public interface PeriodLockPort {

  Optional<String> lockedPeriodLabel(UUID tenantId, LocalDate day);
}
