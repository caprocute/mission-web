package vn.viettel.okrkpi.workflow.infrastructure.reconcile;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.OrgReplicaReconciler;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantSql;

/**
 * Lập lịch phần so của R1 cho bản sao của workflow-service: mặc định mỗi 10 phút, lấy các lần chạy
 * {@code DANG_CHAY} đã qua thời gian lắng (mặc định 15 phút), lần lượt từng tenant.
 * workflow-service không giữ danh bạ tenant như mission-core, nên danh sách tenant có lần chạy chờ
 * so lấy qua hàm {@code org_replica.fn_reconcile_pending_tenants} (V14, chỉ trả mã tenant). Phần
 * phát băm hằng đêm vẫn là CronJob {@code doi-soat-r1} của org-service; workflow-service nhận cùng
 * {@code KET_THUC} qua nhóm consumer riêng.
 */
public class OrgReplicaReconcileJob {

  private static final Logger log = LoggerFactory.getLogger(OrgReplicaReconcileJob.class);
  private static final int BATCH = 20;
  private static final int TENANTS = 500;
  private static final String SET_LOCAL = "select set_config(?, ?, true)";

  private final OrgReplicaReconciler reconciler;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Duration settle;
  private final Clock clock;

  public OrgReplicaReconcileJob(
      OrgReplicaReconciler reconciler,
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      Duration settle,
      Clock clock) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(tm);
    this.settle = Objects.requireNonNull(settle, "settle");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      fixedDelayString = "${mission.reconcile.org-replica.interval:PT10M}",
      initialDelayString = "${mission.reconcile.org-replica.initial-delay:PT2M}")
  public void run() {
    List<UUID> tenants;
    try {
      tenants = pendingTenants();
    } catch (RuntimeException e) {
      log.warn("Không đọc được danh sách tenant chờ đối soát R1", e);
      return;
    }
    runFor(tenants);
  }

  /** Tenant có lần chạy R1 đã qua thời gian lắng mà chưa so. */
  public List<UUID> pendingTenants() {
    return jdbc.queryForList(
        "SELECT org_replica.fn_reconcile_pending_tenants(?, ?, ?)",
        UUID.class,
        OrgReplicaReconciler.JOB,
        Timestamp.from(clock.instant().minus(settle)),
        TENANTS);
  }

  /**
   * So các lần chạy đến hạn của từng tenant; lỗi một tenant không chặn tenant khác. Trả số đã so.
   */
  public int runFor(List<UUID> tenants) {
    int done = 0;
    for (UUID tenant : tenants) {
      try {
        Integer n =
            TenantContext.call(
                TenantId.of(tenant),
                () ->
                    tx.execute(
                        s -> {
                          jdbc.queryForObject(
                              SET_LOCAL,
                              String.class,
                              TenantSql.DEFAULT_SETTING,
                              tenant.toString());
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
        log.warn("Đối soát R1 bản sao tổ chức của workflow-service lỗi ở tenant {}", tenant, e);
      }
    }
    return done;
  }
}
