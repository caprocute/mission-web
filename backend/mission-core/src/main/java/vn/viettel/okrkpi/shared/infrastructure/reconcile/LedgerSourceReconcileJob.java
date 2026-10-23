package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/** Lập lịch R2 hằng đêm (mặc định 02:30) cho ngày hôm trước, mọi tenant của cell. */
public class LedgerSourceReconcileJob {

  private final LedgerSourceReconciler reconciler;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final ZoneId zone;
  private final Clock clock;

  public LedgerSourceReconcileJob(
      LedgerSourceReconciler reconciler,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      ZoneId zone,
      Clock clock) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.zone = Objects.requireNonNull(zone, "zone");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      cron = "${mission.reconcile.ledger.cron:0 30 2 * * *}",
      zone = "${mission.reconcile.ledger.zone:Asia/Ho_Chi_Minh}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return;
    }
    runFor(
        LocalDate.now(clock.withZone(zone)).minusDays(1),
        tenants.snapshotByCode().values().stream()
            .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
            .map(TenantCellRoute::tenantId)
            .distinct()
            .map(TenantId::of)
            .toList());
  }

  /**
   * Dùng trong kiểm thử và chạy tay: đối soát một ngày (theo múi giờ của job) cho danh sách tenant.
   */
  public List<LedgerSourceReconciler.Outcome> runFor(LocalDate day, List<TenantId> tenants) {
    Instant from = day.atStartOfDay(zone).toInstant();
    Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
    return reconciler.run(day, from, to, tenants);
  }
}
