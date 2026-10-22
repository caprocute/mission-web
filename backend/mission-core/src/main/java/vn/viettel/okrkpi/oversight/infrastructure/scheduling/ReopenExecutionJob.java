package vn.viettel.okrkpi.oversight.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenApprovalService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chuyển yêu cầu {@code DA_DUYET} sang {@code DA_THUC_HIEN} khi kỳ đã ở {@code MO_LAI_HAU_KIEM}
 * (MOD-08 không phát sự kiện xác nhận, CORE-07-08). Đi lần lượt từng tenant của cell; chạy lại
 * nhiều lần vẫn an toàn vì cập nhật theo {@code row_version}.
 */
public class ReopenExecutionJob {

  private static final Logger log = LoggerFactory.getLogger(ReopenExecutionJob.class);

  private final ReopenApprovalService approvals;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public ReopenExecutionJob(
      ReopenApprovalService approvals, ObjectProvider<TenantDirectory> directory, String cell) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(fixedDelayString = "${mission.oversight.reopen-execution.delay:PT1M}")
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

  /** Chạy cho danh sách tenant; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += approvals.confirmExecuted(tenant));
      } catch (RuntimeException e) {
        log.warn("Xác nhận mở lại kỳ lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
