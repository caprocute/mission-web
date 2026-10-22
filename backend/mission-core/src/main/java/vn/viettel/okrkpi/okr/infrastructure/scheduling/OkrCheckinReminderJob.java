package vn.viettel.okrkpi.okr.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.okr.application.OkrCheckinReminders;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ nhắc check-in OKR (CORE-01-11): đi lần lượt từng tenant của cell như {@code
 * PlanDeadlineJob}. Chạy mỗi ngày lúc 07:00 giờ Việt Nam và thêm hai lần thử lại trong ngày; chỉ
 * ngày nhắc (thứ Năm hoặc ngày làm việc kế tiếp) tới Chủ nhật mới có việc, khoá giữ chỗ bảo đảm mỗi
 * KR mỗi chu kỳ chỉ được nhắc một lần.
 */
public class OkrCheckinReminderJob {

  private static final Logger log = LoggerFactory.getLogger(OkrCheckinReminderJob.class);

  private final OkrCheckinReminders reminders;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public OkrCheckinReminderJob(
      OkrCheckinReminders reminders, ObjectProvider<TenantDirectory> directory, String cell) {
    this.reminders = Objects.requireNonNull(reminders, "reminders");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(
      cron = "${mission.okr.checkin-reminder.cron:0 0 7,10,14 * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua tác vụ nhắc check-in OKR vì chưa có danh bạ tenant");
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
      log.info("Đã ghi {} yêu cầu nhắc check-in OKR", total);
    }
  }

  /** Chạy cho danh sách tenant cho trước; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += reminders.run(tenant));
      } catch (RuntimeException e) {
        log.warn("Tác vụ nhắc check-in OKR lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
