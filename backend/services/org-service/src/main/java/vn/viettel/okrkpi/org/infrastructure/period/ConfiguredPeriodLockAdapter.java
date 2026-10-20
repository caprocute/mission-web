package vn.viettel.okrkpi.org.infrastructure.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.port.PeriodLockPort;

/**
 * Hiện thực tạm của {@link PeriodLockPort}: danh sách kỳ đã khoá lấy từ cấu hình {@code okrkpi.org.locked-periods}.
 * Điểm chờ: kỳ là dữ liệu MOD-08 ({@code config.period}); hiện thực đích nhận EVT-09 KyDaKhoa, EVT-26 KyDaHuyKhoa.
 */
@Component
public class ConfiguredPeriodLockAdapter implements PeriodLockPort {

  private final Properties properties;

  public ConfiguredPeriodLockAdapter(Properties properties) {
    this.properties = properties;
  }

  @Override
  public Optional<String> lockedPeriodLabel(UUID tenantId, LocalDate day) {
    return properties.lockedPeriods().stream()
        .filter(p -> p.tenantId() == null || p.tenantId().equals(tenantId))
        .filter(p -> !day.isBefore(p.startDate()) && !day.isAfter(p.endDate()))
        .map(LockedPeriod::label)
        .findFirst();
  }

  /** Cấu hình kỳ đã khoá. */
  @ConfigurationProperties("okrkpi.org")
  public record Properties(List<LockedPeriod> lockedPeriods) {

    public Properties {
      lockedPeriods = lockedPeriods == null ? List.of() : List.copyOf(lockedPeriods);
    }
  }

  /** Kỳ đã khoá: nhãn hiển thị (ví dụ "Quý I/2027"), ngày đầu và ngày cuối kỳ. */
  public record LockedPeriod(UUID tenantId, String label, LocalDate startDate, LocalDate endDate) {
  }
}
