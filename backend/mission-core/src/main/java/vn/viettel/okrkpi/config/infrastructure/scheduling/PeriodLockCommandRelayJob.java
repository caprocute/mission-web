package vn.viettel.okrkpi.config.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.config.infrastructure.client.PeriodLockCommandRelay;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chuyển lại hộp lệnh khoá kỳ (CORE-08-05): lệnh nào chưa chuyển được tới saga chốt kỳ ngay sau khi
 * commit (SVC-06 không phản hồi, 5xx) được gửi lại với cùng {@code Idempotency-Key}. Đi lần lượt
 * từng tenant của cell vì RLS.
 */
public class PeriodLockCommandRelayJob {

  private static final Logger log = LoggerFactory.getLogger(PeriodLockCommandRelayJob.class);

  private final PeriodLockCommandRelay relay;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public PeriodLockCommandRelayJob(
      PeriodLockCommandRelay relay, ObjectProvider<TenantDirectory> directory, String cell) {
    this.relay = Objects.requireNonNull(relay, "relay");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(fixedDelayString = "${mission.config.period-lock.relay.delay:30000}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua chuyển hộp lệnh khoá kỳ vì chưa có danh bạ tenant");
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

  /** Chạy cho danh sách tenant cho trước; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += relay.relay(tenant));
      } catch (RuntimeException e) {
        log.warn("Chuyển hộp lệnh khoá kỳ lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
