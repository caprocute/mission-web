package vn.viettel.okrkpi.appraisal.infrastructure.job;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.appraisal.application.decision.DecisionSigningService;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.LockCandidate;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ khoá hồ sơ khi hết hạn khiếu nại (QT-09 bước 14, CORE-04-08): đi lần lượt từng tenant của
 * cell như {@code PlanDeadlineJob}, duyệt hồ sơ {@code DA_KY_SO} theo trang 500 dòng; mỗi hồ sơ
 * khoá trong giao dịch riêng nên lỗi một hồ sơ không chặn hồ sơ khác và chạy lại an toàn.
 */
public class ComplaintDeadlineJob {

  private static final Logger log = LoggerFactory.getLogger(ComplaintDeadlineJob.class);
  static final int PAGE = 500;

  private final DecisionSigningService service;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;
  private final Clock clock;

  public ComplaintDeadlineJob(
      DecisionSigningService service,
      ObjectProvider<TenantDirectory> directory,
      String cell,
      Clock clock) {
    this.service = Objects.requireNonNull(service, "service");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Scheduled(
      cron = "${mission.appraisal.complaint-deadline.cron:0 15 0,6,12 * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void run() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      log.debug("Bỏ qua tác vụ khoá hồ sơ hết hạn khiếu nại vì chưa có danh bạ tenant");
      return;
    }
    int total =
        runFor(
            tenants.snapshotByCode().values().stream()
                .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
                .map(TenantCellRoute::tenantId)
                .distinct()
                .map(TenantId::of)
                .toList());
    if (total > 0) {
      log.info("Đã khoá {} hồ sơ đánh giá hết hạn khiếu nại", total);
    }
  }

  /** Chạy cho danh sách tenant cho trước; lỗi của một tenant không chặn tenant khác. */
  public int runFor(List<TenantId> tenants) {
    LocalDate today =
        LocalDate.now(clock.withZone(vn.viettel.okrkpi.shared.kernel.Clock.BUSINESS_ZONE));
    int[] total = {0};
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> total[0] += lockTenant(tenant, today));
      } catch (RuntimeException e) {
        log.warn("Tác vụ khoá hồ sơ hết hạn khiếu nại lỗi ở tenant {}", tenant, e);
      }
    }
    return total[0];
  }

  private int lockTenant(TenantId tenant, LocalDate today) {
    Map<UUID, Integer> days = new HashMap<>();
    int locked = 0;
    UUID after = null;
    while (true) {
      List<LockCandidate> page = service.candidates(tenant, after, PAGE);
      for (LockCandidate c : page) {
        try {
          if (service.lockIfDue(tenant, c, today, days)) {
            locked++;
          }
        } catch (RuntimeException e) {
          log.warn("Không khoá được hồ sơ {}", c.profileId(), e);
        }
      }
      if (page.size() < PAGE) {
        return locked;
      }
      after = page.get(page.size() - 1).profileId();
    }
  }
}
