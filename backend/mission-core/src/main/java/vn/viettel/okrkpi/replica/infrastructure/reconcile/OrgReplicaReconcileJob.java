package vn.viettel.okrkpi.replica.infrastructure.reconcile;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Lập lịch phần so của R1: mặc định mỗi 10 phút, lấy các lần chạy {@code DANG_CHAY} đã qua thời
 * gian lắng (mặc định 15 phút, đủ để thông điệp thực thể của một lần phát lại thật được áp), lần
 * lượt từng tenant của cell. Phần phát băm hằng đêm là CronJob {@code doi-soat-r1} của chart
 * org-service.
 */
public class OrgReplicaReconcileJob {

  private static final Logger log = LoggerFactory.getLogger(OrgReplicaReconcileJob.class);
  private static final int BATCH = 20;

  private final OrgReplicaReconciler reconciler;
  private final TransactionTemplate tx;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Duration settle;
  private final Clock clock;

  public OrgReplicaReconcileJob(
      OrgReplicaReconciler reconciler,
      PlatformTransactionManager tm,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      Duration settle,
      Clock clock) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.tx = new TransactionTemplate(tm);
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.settle = Objects.requireNonNull(settle, "settle");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      fixedDelayString = "${mission.reconcile.org-replica.interval:PT10M}",
      initialDelayString = "${mission.reconcile.org-replica.initial-delay:PT2M}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
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

  /**
   * So các lần chạy đến hạn của từng tenant; lỗi một tenant không chặn tenant khác. Trả số đã so.
   */
  public int runFor(List<TenantId> tenants) {
    int done = 0;
    for (TenantId tenant : tenants) {
      try {
        Integer n =
            TenantContext.call(
                tenant,
                () ->
                    tx.execute(
                        s -> {
                          List<ReconcileRun> due =
                              reconciler
                                  .runs()
                                  .pending(
                                      OrgReplicaReconciler.JOB,
                                      clock.instant().minus(settle),
                                      BATCH);
                          due.forEach(reconciler::check);
                          return due.size();
                        }));
        done += n == null ? 0 : n;
      } catch (Exception e) {
        log.warn("Đối soát R1 lỗi ở tenant {}", tenant, e);
      }
    }
    return done;
  }
}
