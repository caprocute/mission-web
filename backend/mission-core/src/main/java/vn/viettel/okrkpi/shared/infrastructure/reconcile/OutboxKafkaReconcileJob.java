package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * Lập lịch R7 hằng giờ: mặc định phút 15 mỗi giờ, so giờ tròn vừa qua cho mọi tenant của cell (phút
 * 15 để Kafka đã nhận hết thông điệp của giờ trước cộng thời gian nới).
 */
public class OutboxKafkaReconcileJob {

  private final OutboxKafkaReconciler reconciler;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Clock clock;

  public OutboxKafkaReconcileJob(
      OutboxKafkaReconciler reconciler,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      Clock clock) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      cron = "${mission.reconcile.outbox.cron:0 15 * * * *}",
      zone = "${mission.reconcile.outbox.zone:Asia/Ho_Chi_Minh}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return;
    }
    Instant to = clock.instant().truncatedTo(ChronoUnit.HOURS);
    reconciler.run(
        to.minus(Duration.ofHours(1)),
        to,
        tenants.snapshotByCode().values().stream()
            .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
            .map(TenantCellRoute::tenantId)
            .distinct()
            .map(TenantId::of)
            .toList());
  }

  /** Dùng trong kiểm thử và chạy tay: so một cửa sổ cho danh sách tenant. */
  public List<OutboxKafkaReconciler.Outcome> runFor(
      Instant from, Instant to, List<TenantId> tenants) {
    return reconciler.run(from, to, tenants);
  }
}
