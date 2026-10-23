package vn.viettel.okrkpi.oversight.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chuyển khiếu nại {@code DANG_GIAI_TRINH} sang {@code CHO_KET_LUAN} khi mọi yêu cầu giải trình đã
 * trả lời hoặc quá hạn (QT-12 "Ngoại lệ", CORE-07-04). Đi lần lượt từng tenant của cell; chạy lại
 * vẫn an toàn vì cập nhật theo {@code row_version}. Nhắc hạn và chuyển cấp thuộc SVC-06.
 */
public class ComplaintExplanationJob {

  static final int BATCH = 200;

  private static final Logger log = LoggerFactory.getLogger(ComplaintExplanationJob.class);

  private final ComplaintResolutionService service;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public ComplaintExplanationJob(
      ComplaintResolutionService service, ObjectProvider<TenantDirectory> directory, String cell) {
    this.service = Objects.requireNonNull(service, "service");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(fixedDelayString = "${mission.oversight.complaint-explanation.delay:PT15M}")
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
        TenantContext.run(tenant, () -> total[0] += service.settleExplanations(tenant, BATCH));
      } catch (RuntimeException e) {
        log.warn("Chuyển khiếu nại quá hạn giải trình lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
