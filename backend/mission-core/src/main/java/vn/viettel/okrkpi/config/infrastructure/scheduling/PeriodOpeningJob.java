package vn.viettel.okrkpi.config.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ mở kỳ (CORE-08-02, suy luận từ TL-02a dòng 2156): kỳ "Đã tạo" tới ngày bắt đầu thì phát
 * {@code PeriodOpenedEvent} để các mô-đun chuẩn bị dữ liệu kỳ. Kỳ luôn ở {@code MO} từ lúc tạo nên
 * tác vụ không đổi trạng thái.
 *
 * <p>RLS ép buộc theo {@code app.tenant_id} nên tác vụ đi lần lượt từng tenant của cell trong
 * {@link TenantDirectory}; mỗi tenant chạy trong {@link TenantContext} riêng. Chạy idempotent:
 * nhiều pod cùng chạy hay chạy lại vẫn chỉ phát một lần mỗi kỳ.
 */
public class PeriodOpeningJob {

  private static final Logger log = LoggerFactory.getLogger(PeriodOpeningJob.class);

  private final PeriodConfigService periods;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  /**
   * @param cell mã cell của tiến trình; trống là mọi tenant trong danh bạ
   */
  public PeriodOpeningJob(
      PeriodConfigService periods, ObjectProvider<TenantDirectory> directory, String cell) {
    this.periods = Objects.requireNonNull(periods, "periods");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(
      cron = "${mission.config.period-opening.cron:0 5 0,6,12,18 * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua tác vụ mở kỳ vì chưa có danh bạ tenant");
      return;
    }
    int total = runFor(tenantsOfCell(tenants));
    if (total > 0) {
      log.info("Đã phát sự kiện mở kỳ cho {} kỳ", total);
    }
  }

  /** Chạy cho danh sách tenant cho trước; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += periods.announceOpenings(tenant));
      } catch (RuntimeException e) {
        log.warn("Tác vụ mở kỳ lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }

  private List<TenantId> tenantsOfCell(TenantDirectory tenants) {
    return tenants.snapshotByCode().values().stream()
        .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
        .map(TenantCellRoute::tenantId)
        .distinct()
        .map(TenantId::of)
        .toList();
  }
}
