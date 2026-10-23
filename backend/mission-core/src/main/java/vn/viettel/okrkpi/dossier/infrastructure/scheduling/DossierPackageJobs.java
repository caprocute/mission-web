package vn.viettel.okrkpi.dossier.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ định kỳ của gói hồ sơ số (CORE-06-06): {@link #settle} vài giây một lần tính lại trạng
 * thái các gói vừa nhận EVT-17 (theo lô, không đếm lại cả gói ở mỗi sự kiện); {@link #track} mỗi
 * phút đi từng tenant của cell như {@code ComplaintDeadlineJob}, đánh lỗi biểu mẫu của lệnh sinh đã
 * kết thúc và tính lại mọi gói đang sinh (lưới an toàn khi pod dừng trước khi kịp tính lại).
 */
public class DossierPackageJobs {

  private static final Logger LOG = LoggerFactory.getLogger(DossierPackageJobs.class);
  static final int PAGE = 200;

  private final DossierPackageService service;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public DossierPackageJobs(
      DossierPackageService service, ObjectProvider<TenantDirectory> directory, String cell) {
    this.service = Objects.requireNonNull(service, "service");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(fixedDelayString = "${mission.dossier.package.settle-delay:PT2S}")
  public void settle() {
    int changed = service.settle();
    if (changed > 0) {
      LOG.debug("Đã tính lại trạng thái {} gói hồ sơ", changed);
    }
  }

  @Scheduled(
      fixedDelayString = "${mission.dossier.package.track-delay:PT1M}",
      initialDelayString = "${mission.dossier.package.track-initial-delay:PT1M}")
  public void track() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return;
    }
    trackFor(
        tenants.snapshotByCode().values().stream()
            .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
            .map(TenantCellRoute::tenantId)
            .distinct()
            .map(TenantId::of)
            .toList());
  }

  /** Theo dõi cho danh sách tenant; lỗi của một tenant không chặn tenant khác. */
  public int trackFor(List<TenantId> tenants) {
    int[] failed = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> failed[0] += service.track(tenant, PAGE));
      } catch (RuntimeException e) {
        LOG.warn("Không theo dõi được gói hồ sơ của tenant {}", tenant, e);
      }
    }
    if (failed[0] > 0) {
      LOG.info("Đánh lỗi {} biểu mẫu không sinh được", failed[0]);
    }
    return failed[0];
  }
}
