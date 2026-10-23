package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/** Lập lịch R5 hằng giờ (mặc định phút 35) cho mọi tenant của cell. */
public class AnalyticsReconcileJob {

  private final AnalyticsReconciler reconciler;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public AnalyticsReconcileJob(
      AnalyticsReconciler reconciler, ObjectProvider<TenantDirectory> directory, String cell) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(
      cron = "${mission.reconcile.analytics.cron:0 35 * * * *}",
      zone = "${mission.reconcile.analytics.zone:Asia/Ho_Chi_Minh}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return;
    }
    reconciler.run(
        tenants.snapshotByCode().values().stream()
            .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
            .map(TenantCellRoute::tenantId)
            .distinct()
            .map(TenantId::of)
            .toList());
  }

  /** Dùng trong kiểm thử và chạy tay. */
  public List<AnalyticsReconciler.Outcome> runFor(List<TenantId> tenants) {
    return reconciler.run(tenants);
  }
}
