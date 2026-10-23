package vn.viettel.okrkpi.dossier.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierExportService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ định kỳ của xuất gói và gửi gói con (CORE-06-08): mỗi lượt đi từng tenant của cell, gửi
 * gói con chưa gửi và hỏi SVC-13 kết quả các gói con đang chờ (DV-13-05 không phát sự kiện kết
 * quả), rồi giao lại tác vụ xuất bị bỏ dở khi pod dừng hoặc hàng đợi đầy.
 */
public class DossierTransferJobs {

  private static final Logger LOG = LoggerFactory.getLogger(DossierTransferJobs.class);
  static final int PAGE = 200;

  private final DossierExportService exports;
  private final DossierTransferService transfers;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public DossierTransferJobs(
      DossierExportService exports,
      DossierTransferService transfers,
      ObjectProvider<TenantDirectory> directory,
      String cell) {
    this.exports = Objects.requireNonNull(exports, "exports");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(
      fixedDelayString = "${mission.dossier.transfer.poll-delay:PT1M}",
      initialDelayString = "${mission.dossier.transfer.poll-initial-delay:PT1M}")
  public void poll() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return;
    }
    pollFor(
        tenants.snapshotByCode().values().stream()
            .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
            .map(TenantCellRoute::tenantId)
            .distinct()
            .map(TenantId::of)
            .toList());
  }

  /** Một lượt cho danh sách tenant; lỗi của một tenant không chặn tenant khác. */
  public int pollFor(List<TenantId> tenants) {
    int[] handled = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(
            tenant, () -> handled[0] += transfers.poll(tenant, PAGE) + exports.sweep(tenant, PAGE));
      } catch (RuntimeException e) {
        LOG.warn("Không theo dõi được xuất, gửi gói hồ sơ của tenant {}", tenant, e);
      }
    }
    return handled[0];
  }
}
