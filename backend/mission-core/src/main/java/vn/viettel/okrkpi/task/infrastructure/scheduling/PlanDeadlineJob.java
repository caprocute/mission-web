package vn.viettel.okrkpi.task.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.plan.PlanDeadlineReminders;

/**
 * Tác vụ nhắc hạn duyệt kế hoạch quý (BR-04-01, CORE-02-07): đi lần lượt từng tenant của cell như
 * {@code PeriodOpeningJob}; chạy vài lần trong ngày để có lần thử lại, khoá giữ chỗ bảo đảm mỗi mốc
 * chỉ gửi một lần.
 */
public class PlanDeadlineJob {

  private static final Logger log = LoggerFactory.getLogger(PlanDeadlineJob.class);

  private final PlanDeadlineReminders reminders;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public PlanDeadlineJob(
      PlanDeadlineReminders reminders, ObjectProvider<TenantDirectory> directory, String cell) {
    this.reminders = Objects.requireNonNull(reminders, "reminders");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(
      cron = "${mission.task.plan-deadline.cron:0 30 7,10,14 * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua tác vụ nhắc hạn duyệt kế hoạch vì chưa có danh bạ tenant");
      return;
    }
    int total =
        runFor(
            tenants.snapshotByCode().values().stream()
                .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
                .map(TenantCellRoute::tenantId)
                .distinct()
                .map(TenantId::of)
                .toList());
    if (total > 0) {
      log.info("Đã ghi {} thông báo hạn duyệt kế hoạch", total);
    }
  }

  /** Chạy cho danh sách tenant cho trước; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += reminders.run(tenant));
      } catch (RuntimeException e) {
        log.warn("Tác vụ nhắc hạn duyệt kế hoạch lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
