package vn.viettel.okrkpi.task.infrastructure.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.config.api.HolidayCalendarChangedEvent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.overdue.OverdueSweeper;

/**
 * Tác vụ đặt cờ quá hạn lúc 00:05 giờ Việt Nam (QT-06 bước 8, CORE-02-23), đi lần lượt từng tenant
 * của cell như {@link PlanDeadlineJob}; nghe {@link HolidayCalendarChangedEvent} của MOD-08 để tính
 * lại cờ cho tenant có lịch nghỉ đổi.
 */
public class OverdueJob {

  private static final Logger log = LoggerFactory.getLogger(OverdueJob.class);
  private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  private final OverdueSweeper sweeper;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Clock clock;

  public OverdueJob(
      OverdueSweeper sweeper, ObjectProvider<TenantDirectory> directory, String cell, Clock clock) {
    this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(cron = "${mission.task.overdue.cron:0 5 0 * * *}", zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua tác vụ đặt cờ quá hạn vì chưa có danh bạ tenant");
      return;
    }
    runFor(
        tenants.snapshotByCode().values().stream()
            .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
            .map(TenantCellRoute::tenantId)
            .distinct()
            .map(TenantId::of)
            .toList());
  }

  @EventListener
  public void on(HolidayCalendarChangedEvent e) {
    runFor(List.of(e.tenantId()));
  }

  /** Chạy cho danh sách tenant; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    LocalDate today = LocalDate.now(clock.withZone(VN));
    int total = 0;
    for (TenantId tenant : tenants) {
      try {
        OverdueSweeper.Outcome o = TenantContext.call(tenant, () -> sweeper.sweep(tenant, today));
        total += o.flagged();
        if (o.flagged() + o.cleared() > 0) {
          log.info(
              "Tenant {}: {} nhiệm vụ mới quá hạn, {} nhiệm vụ hết quá hạn",
              tenant,
              o.flagged(),
              o.cleared());
        }
      } catch (Exception e) {
        log.warn("Tác vụ đặt cờ quá hạn lỗi ở tenant {}", tenant, e);
      }
    }
    return total;
  }
}
