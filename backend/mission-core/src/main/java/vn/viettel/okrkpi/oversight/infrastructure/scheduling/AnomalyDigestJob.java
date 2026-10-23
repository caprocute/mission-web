package vn.viettel.okrkpi.oversight.infrastructure.scheduling;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyDigest;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Thông báo tổng hợp sau đợt job phát hiện đêm (job SVC-15 chạy 02:00 theo cell): mặc định 03:30
 * giờ Việt Nam (suy luận). Đi lần lượt từng tenant của cell; chạy lại an toàn vì cảnh báo đã báo
 * được đánh dấu.
 */
public class AnomalyDigestJob {

  private static final Logger log = LoggerFactory.getLogger(AnomalyDigestJob.class);

  private final AnomalyDigest digest;
  private final TransactionTemplate tx;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Clock clock;

  public AnomalyDigestJob(
      AnomalyDigest digest,
      PlatformTransactionManager tm,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      Clock clock) {
    this.digest = Objects.requireNonNull(digest, "digest");
    this.tx = new TransactionTemplate(tm);
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      cron = "${mission.oversight.anomaly-digest.cron:0 30 3 * * *}",
      zone = "${mission.oversight.anomaly-digest.zone:Asia/Ho_Chi_Minh}")
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
   * Chạy cho danh sách tenant; lỗi của một tenant không chặn tenant khác. Trả số người được báo.
   */
  public int runFor(List<TenantId> tenants) {
    LocalDate day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh")));
    int total = 0;
    for (TenantId tenant : tenants) {
      UUID batch =
          UUID.nameUUIDFromBytes(
              ("anomaly-digest|" + tenant.value() + "|" + day + "|" + clock.millis())
                  .getBytes(StandardCharsets.UTF_8));
      try {
        Integer n = inTenant(tenant, () -> tx.execute(s -> digest.run(tenant, batch)));
        total += n == null ? 0 : n;
      } catch (RuntimeException e) {
        log.warn("Thông báo tổng hợp cảnh báo lỗi ở tenant {}", tenant, e);
      }
    }
    return total;
  }

  private static <T> T inTenant(TenantId tenant, java.util.concurrent.Callable<T> work) {
    try {
      return TenantContext.call(tenant, work);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
