package vn.viettel.okrkpi.scoring.infrastructure.reconcile;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lập lịch R4: lấy mẫu hằng tuần (mặc định 02:00 Chủ nhật giờ Việt Nam), so kết quả tính lại hằng
 * giờ cho tới khi các tác vụ kiểm tra xong. Danh sách tenant của cell lấy từ cấu hình {@code
 * mission.reconcile.recompute.tenants} (chart đặt theo cell), vì SVC-07 không giữ danh bạ tenant.
 */
public class ScoreRecomputeReconcileJob {

  private static final Logger log = LoggerFactory.getLogger(ScoreRecomputeReconcileJob.class);

  private final ScoreRecomputeReconciler reconciler;
  private final List<TenantId> tenants;

  public ScoreRecomputeReconcileJob(ScoreRecomputeReconciler reconciler, List<TenantId> tenants) {
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.tenants = List.copyOf(tenants);
  }

  @Scheduled(
      cron = "${mission.reconcile.recompute.sample-cron:0 0 2 * * SUN}",
      zone = "${mission.reconcile.recompute.zone:Asia/Ho_Chi_Minh}")
  public void sample() {
    for (TenantId t : tenants) {
      try {
        reconciler.sample(t);
      } catch (RuntimeException e) {
        log.warn("Lấy mẫu R4 lỗi ở tenant {}", t.value(), e);
      }
    }
  }

  @Scheduled(
      cron = "${mission.reconcile.recompute.evaluate-cron:0 20 * * * *}",
      zone = "${mission.reconcile.recompute.zone:Asia/Ho_Chi_Minh}")
  public void evaluate() {
    for (TenantId t : tenants) {
      try {
        reconciler.evaluate(t);
      } catch (RuntimeException e) {
        log.warn("So kết quả R4 lỗi ở tenant {}", t.value(), e);
      }
    }
  }
}
