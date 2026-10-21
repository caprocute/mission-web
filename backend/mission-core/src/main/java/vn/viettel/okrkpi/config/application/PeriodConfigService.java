package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodOpenedEvent;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng của {@code MOD-08 · Configuration & Period} cài đặt cổng {@link PeriodApi} và
 * phát sự kiện nội bộ {@link PeriodOpenedEvent} qua Spring Modulith Event Publication Registry.
 */
@Service
public class PeriodConfigService implements PeriodApi {

  private final ApplicationEventPublisher eventPublisher;
  private final Map<PeriodKey, PeriodSnapshot> periods = new ConcurrentHashMap<>();

  public PeriodConfigService(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
  }

  /**
   * Đăng ký hoặc cập nhật ảnh chụp kỳ đánh giá trong bộ nhớ đệm của mô-đun {@code config}.
   *
   * @param snapshot ảnh chụp kỳ đánh giá
   */
  public void registerPeriod(PeriodSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    periods.put(new PeriodKey(snapshot.tenantId(), snapshot.id()), snapshot);
  }

  /**
   * Mở kỳ đánh giá và phát sự kiện {@link PeriodOpenedEvent} trong giao dịch để Spring Modulith ghi
   * vào {@code modulith.event_publication}.
   *
   * @param snapshot ảnh chụp kỳ đánh giá được mở
   */
  @Transactional
  public void openPeriod(PeriodSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    PeriodSnapshot opened =
        new PeriodSnapshot(
            snapshot.id(),
            snapshot.tenantId(),
            snapshot.code(),
            snapshot.name(),
            snapshot.startDate(),
            snapshot.endDate(),
            PeriodStatus.MO);
    periods.put(new PeriodKey(opened.tenantId(), opened.id()), opened);
    eventPublisher.publishEvent(
        new PeriodOpenedEvent(opened.id(), opened.tenantId(), opened.code(), Instant.now()));
  }

  @Override
  public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    return periods.values().stream()
        .filter(p -> p.tenantId().equals(tenantId) && p.status() == PeriodStatus.MO)
        .findFirst();
  }

  @Override
  public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    return Optional.ofNullable(periods.get(new PeriodKey(tenantId, periodId)));
  }

  @Override
  public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    PeriodSnapshot snapshot = periods.get(new PeriodKey(tenantId, periodId));
    if (snapshot != null && snapshot.isLocked()) {
      throw new PeriodLockedException(periodId);
    }
  }

  private record PeriodKey(TenantId tenantId, PeriodId periodId) {}
}
