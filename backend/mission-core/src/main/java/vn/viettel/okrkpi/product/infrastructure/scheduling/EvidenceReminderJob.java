package vn.viettel.okrkpi.product.infrastructure.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.product.application.evidence.EvidenceReminderService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ nhắc quá hạn nghiệm thu hằng ngày (BR-07-12, CORE-03-08), 08:00 giờ Việt Nam. Đi lần lượt
 * từng tenant của cell trong {@link TenantDirectory}; mỗi tenant chạy trong {@link TenantContext}
 * riêng. Nhiều pod cùng chạy hay chạy lại vẫn chỉ phát một lời nhắc mỗi người mỗi ngày nhờ sổ giữ
 * chỗ của {@link EvidenceReminderService}.
 */
public class EvidenceReminderJob {

  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final Logger log = LoggerFactory.getLogger(EvidenceReminderJob.class);

  private final EvidenceReminderService reminders;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Clock clock;

  public EvidenceReminderJob(
      EvidenceReminderService reminders,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      Clock clock) {
    this.reminders = Objects.requireNonNull(reminders, "reminders");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      cron = "${mission.product.acceptance-reminder.cron:0 0 8 * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua tác vụ nhắc nghiệm thu vì chưa có danh bạ tenant");
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    int sent =
        runFor(
            tenants.snapshotByCode().values().stream()
                .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
                .map(TenantCellRoute::tenantId)
                .distinct()
                .map(TenantId::of)
                .toList(),
            today);
    if (sent > 0) {
      log.info("Đã nhắc quá hạn nghiệm thu {} lượt", sent);
    }
  }

  /** Chạy cho danh sách tenant; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants, LocalDate today) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(
            tenant,
            () -> {
              EvidenceReminderService.Result r = reminders.remind(tenant, today);
              total[0] += r.reviewers() + r.summaries();
            });
      } catch (RuntimeException e) {
        log.warn("Tác vụ nhắc nghiệm thu lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
