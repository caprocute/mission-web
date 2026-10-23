package vn.viettel.okrkpi.evidence.infrastructure.reconcile;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lập lịch R6: mẫu hằng ngày (mặc định 03:30 giờ Việt Nam), quét toàn bộ ngày đầu mỗi quý (01:00).
 * Danh sách tenant của cell lấy từ {@code mission.reconcile.evidence.tenants} (chart đặt theo
 * cell), vì SVC-08 không giữ danh bạ tenant.
 */
public class EvidenceObjectReconcileJob {

  private static final Logger log = LoggerFactory.getLogger(EvidenceObjectReconcileJob.class);

  private final EvidenceObjectReconciler reconciler;
  private final List<TenantId> tenants;

  public EvidenceObjectReconcileJob(EvidenceObjectReconciler reconciler, List<TenantId> tenants) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.tenants = List.copyOf(tenants);
  }

  @Scheduled(
      cron = "${mission.reconcile.evidence.daily-cron:0 30 3 * * *}",
      zone = "${mission.reconcile.evidence.zone:Asia/Ho_Chi_Minh}")
  public void daily() {
    runAll(EvidenceObjectReconciler.Mode.NGAY);
  }

  @Scheduled(
      cron = "${mission.reconcile.evidence.quarterly-cron:0 0 1 1 1,4,7,10 *}",
      zone = "${mission.reconcile.evidence.zone:Asia/Ho_Chi_Minh}")
  public void quarterly() {
    runAll(EvidenceObjectReconciler.Mode.QUY);
  }

  private void runAll(EvidenceObjectReconciler.Mode mode) {
    for (TenantId t : tenants) {
      try {
        reconciler.run(t, mode);
      } catch (RuntimeException e) {
        log.warn("Đối soát R6 lỗi ở tenant {}", t.value(), e);
      }
    }
  }
}
