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
 * Tự đóng khiếu nại (CORE-07-05, QT-12 bước 12): {@code DA_TRA_LOI} hết 5 ngày làm việc không bảo
 * lưu, {@code KHONG_THU_LY} còn sót, {@code DANG_THI_HANH} khi yêu cầu mở lại gắn kèm đã thực hiện
 * và khoá lại (đọc thẳng {@code oversight.reopen_request} cùng schema, không cần sự kiện). Đi lần
 * lượt từng tenant của cell; chạy lại an toàn vì chỉ đóng dòng chưa đóng, theo {@code row_version}.
 */
public class ComplaintClosingJob {

  static final int BATCH = 200;

  private static final Logger log = LoggerFactory.getLogger(ComplaintClosingJob.class);

  private final ComplaintResolutionService service;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public ComplaintClosingJob(
      ComplaintResolutionService service, ObjectProvider<TenantDirectory> directory, String cell) {
    this.service = Objects.requireNonNull(service, "service");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(fixedDelayString = "${mission.oversight.complaint-closing.delay:PT1H}")
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
        TenantContext.run(tenant, () -> total[0] += service.closeDue(tenant, BATCH));
      } catch (RuntimeException e) {
        log.warn("Tự đóng khiếu nại lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }
}
