package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Lập lịch R3: saga chốt kỳ (DV-06-06) gọi {@link #runFor} sau bước tính điểm; trong khi chờ saga,
 * và để gỡ chặn khi dữ liệu đã được sửa, lịch mặc định 30 phút một lần đối soát lại mọi kỳ có ảnh
 * chụp mới từ lần chạy trước và mọi kỳ có lần chạy gần nhất {@code LOI} (suy luận).
 */
public class ScoreSnapshotReconcileJob {

  private static final Logger log = LoggerFactory.getLogger(ScoreSnapshotReconcileJob.class);

  private final ScoreSnapshotReconciler reconciler;
  private final JdbcTemplate appraisal;
  private final PlatformTransactionManager tm;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Duration lookback;
  private final Clock clock;

  public ScoreSnapshotReconcileJob(
      ScoreSnapshotReconciler reconciler,
      JdbcTemplate appraisal,
      PlatformTransactionManager tm,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      Duration lookback,
      Clock clock) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.appraisal = Objects.requireNonNull(appraisal, "appraisal");
    this.tm = Objects.requireNonNull(tm, "tm");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.lookback = Objects.requireNonNull(lookback, "lookback");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      cron = "${mission.reconcile.score.cron:0 5/30 * * * *}",
      zone = "${mission.reconcile.score.zone:Asia/Ho_Chi_Minh}")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return;
    }
    tenants.snapshotByCode().values().stream()
        .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
        .map(TenantCellRoute::tenantId)
        .distinct()
        .map(TenantId::of)
        .forEach(
            t -> {
              try {
                runTenant(t);
              } catch (RuntimeException e) {
                log.warn("Đối soát R3 lỗi ở tenant {}", t.value(), e);
              }
            });
  }

  /** Đối soát các kỳ cần xem của một tenant; trả số kỳ đã đối soát. */
  public int runTenant(TenantId tenant) {
    List<UUID> periods = due(tenant);
    periods.forEach(p -> reconciler.reconcile(tenant, p));
    return periods.size();
  }

  /** Saga chốt kỳ và chạy tay: đối soát một kỳ. */
  public ScoreSnapshotReconciler.Outcome runFor(TenantId tenant, UUID periodId) {
    return reconciler.reconcile(tenant, periodId);
  }

  private List<UUID> due(TenantId tenant) {
    Instant floor = clock.instant().minus(lookback);
    try {
      return TenantContext.call(
          tenant,
          () ->
              new TransactionTemplate(tm)
                  .execute(
                      st -> {
                        Timestamp since =
                            appraisal
                                .query(
                                    "SELECT max(started_at) FROM appraisal.reconcile_run"
                                        + " WHERE job = ? AND status <> 'DANG_CHAY'",
                                    (rs, i) -> rs.getTimestamp(1),
                                    ScoreSnapshotReconciler.JOB)
                                .stream()
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(Timestamp.from(floor));
                        Set<UUID> out = new LinkedHashSet<>();
                        appraisal.query(
                            "SELECT DISTINCT period_id FROM appraisal.score_snapshot"
                                + " WHERE created_at >= ?",
                            rs -> {
                              out.add(rs.getObject(1, UUID.class));
                            },
                            since);
                        appraisal.query(
                            "SELECT DISTINCT ON (detail ->> 'periodId') detail ->> 'periodId',"
                                + " status FROM appraisal.reconcile_run WHERE job = ?"
                                + " AND status <> 'DANG_CHAY' AND started_at >= ?"
                                + " ORDER BY detail ->> 'periodId', started_at DESC",
                            rs -> {
                              if ("LOI".equals(rs.getString(2)) && rs.getString(1) != null) {
                                out.add(UUID.fromString(rs.getString(1)));
                              }
                            },
                            ScoreSnapshotReconciler.JOB,
                            Timestamp.from(floor));
                        return List.copyOf(out);
                      }));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
