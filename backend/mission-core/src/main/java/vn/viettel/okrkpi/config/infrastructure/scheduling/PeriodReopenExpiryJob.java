package vn.viettel.okrkpi.config.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.config.application.PeriodReopenService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Timer hết hạn mở lại hậu kiểm (CORE-08-06, QT-13 bước 12): tìm kỳ {@code MO_LAI_HAU_KIEM} quá hạn
 * mở (hạn đã tính theo ngày làm việc khi mở) rồi khoá lại. Đi lần lượt từng tenant của cell vì RLS;
 * chạy lại nhiều lần, nhiều pod vẫn chỉ khoá lại một lần mỗi lần mở lại.
 */
public class PeriodReopenExpiryJob {

  /** Tài khoản hệ thống ghi vào {@code updated_by} và nhật ký trạng thái kỳ. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private static final Logger log = LoggerFactory.getLogger(PeriodReopenExpiryJob.class);

  private final PeriodReopenService reopens;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public PeriodReopenExpiryJob(
      PeriodReopenService reopens, ObjectProvider<TenantDirectory> directory, String cell) {
    this.reopens = Objects.requireNonNull(reopens, "reopens");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(
      cron = "${mission.config.period-reopen.expiry-cron:0 */10 * * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua timer hết hạn mở lại vì chưa có danh bạ tenant");
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
      log.info("Đã khoá lại {} kỳ mở lại hậu kiểm quá hạn", total);
    }
  }

  /** Chạy cho danh sách tenant cho trước; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += reopens.expireDue(tenant, SYSTEM_ACTOR));
      } catch (RuntimeException e) {
        log.warn("Timer hết hạn mở lại lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
