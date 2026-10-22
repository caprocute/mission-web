package vn.viettel.okrkpi.product.application.evidence;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderNotices;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderRepository;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderRepository.PendingReview;
import vn.viettel.okrkpi.product.application.evidence.port.ReviewerSupervisors;
import vn.viettel.okrkpi.product.domain.evidence.ReminderRules;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nhắc quá hạn nghiệm thu của QT-07 (BR-07-12, MSG-07-14, CORE-03-08): quét phiên bản chờ nghiệm
 * thu của tenant, đếm ngày làm việc từ ngày nộp theo lịch của tenant ({@link WorkingCalendarApi}),
 * so với tham số {@code acceptance_sla_days} của Policy Pack áp cho kỳ, rồi gom theo người nghiệm
 * thu: mỗi người một EVT-21 mỗi ngày, cấp trên một bản tóm tắt mỗi ngày. Giữ chỗ và sự kiện ghi
 * cùng giao dịch nên chạy lại hay nhiều pod cùng chạy vẫn không phát trùng.
 */
public class EvidenceReminderService {

  static final String TX = EvidenceLifecycleService.TX;
  static final String SLA_KEY = "acceptance_sla_days";
  static final String KIND_REVIEWER = "nhac-nghiem-thu";
  static final String KIND_SUPERVISOR = "tom-tat-nghiem-thu";
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final List<String> FLOWS = List.of("NHA_NUOC", "DANG");

  private final ReminderRepository repository;
  private final ReminderNotices notices;
  private final WorkingCalendarApi calendar;
  private final PolicyQueryApi policies;
  private final ReviewerSupervisors supervisors;

  public EvidenceReminderService(
      ReminderRepository repository,
      ReminderNotices notices,
      WorkingCalendarApi calendar,
      PolicyQueryApi policies,
      ReviewerSupervisors supervisors) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.policies = policies;
    this.supervisors = supervisors;
  }

  /** Kết quả một lượt: số lời nhắc người nghiệm thu và số bản tóm tắt đã phát. */
  public record Result(int reviewers, int summaries) {}

  private record Overdue(int count, int slaDays) {
    Overdue plus(int sla) {
      return new Overdue(count + 1, Math.min(slaDays, sla));
    }
  }

  private record Summary(int count, int reviewers, int slaDays) {
    Summary plus(Overdue o) {
      return new Summary(count + o.count(), reviewers + 1, Math.min(slaDays, o.slaDays()));
    }
  }

  /**
   * Chạy cho một tenant trong ngày {@code today} (giờ Việt Nam); gọi trong {@code TenantContext}.
   */
  @Transactional(TX)
  public Result remind(TenantId tenant, LocalDate today) {
    if (!calendar.isWorkingDay(tenant, today)) {
      return new Result(0, 0);
    }
    Map<UUID, Integer> slaByPeriod = new HashMap<>();
    Map<UUID, Overdue> byReviewer = new TreeMap<>();
    for (PendingReview p : repository.pendingReviews(tenant)) {
      if (p.submittedAt() == null || p.reviewerId() == null) {
        continue;
      }
      int sla = slaByPeriod.computeIfAbsent(p.periodId(), id -> slaDays(tenant, id));
      LocalDate submitted = p.submittedAt().atZone(ZONE).toLocalDate();
      long waited = calendar.countWorkingDays(tenant, submitted, today);
      if (ReminderRules.overdue(waited, sla)) {
        byReviewer.merge(p.reviewerId(), new Overdue(1, sla), (a, b) -> a.plus(b.slaDays()));
      }
    }
    int reminded = 0;
    Map<UUID, Summary> bySupervisor = new LinkedHashMap<>();
    for (Map.Entry<UUID, Overdue> e : byReviewer.entrySet()) {
      UUID reviewer = e.getKey();
      Overdue o = e.getValue();
      if (repository.claim(tenant, reviewer, KIND_REVIEWER, today)) {
        notices.overdueForReviewer(tenant, reviewer, o.count(), o.slaDays(), today);
        reminded++;
      }
      if (supervisors != null) {
        for (UUID boss : supervisors.supervisorsOf(tenant, reviewer, today)) {
          if (!boss.equals(reviewer)) {
            bySupervisor.merge(boss, new Summary(o.count(), 1, o.slaDays()), (a, b) -> a.plus(o));
          }
        }
      }
    }
    int summaries = 0;
    for (Map.Entry<UUID, Summary> e : bySupervisor.entrySet()) {
      if (repository.claim(tenant, e.getKey(), KIND_SUPERVISOR, today)) {
        Summary s = e.getValue();
        notices.overdueSummary(tenant, e.getKey(), s.count(), s.reviewers(), s.slaDays(), today);
        summaries++;
      }
    }
    return new Result(reminded, summaries);
  }

  /** Tham số của gói áp cho kỳ (luồng Nhà nước trước, rồi luồng Đảng); thiếu thì 5 ngày. */
  int slaDays(TenantId tenant, UUID periodId) {
    if (policies == null || periodId == null) {
      return ReminderRules.DEFAULT_SLA_DAYS;
    }
    for (String flow : FLOWS) {
      Optional<ResolvedPack> pack =
          policies.resolve(tenant, PeriodId.of(periodId), flow, null, null);
      if (pack.isPresent()) {
        return ReminderRules.slaDays(
            policies.parameter(tenant, pack.get().versionId(), SLA_KEY).orElse(null));
      }
    }
    return ReminderRules.DEFAULT_SLA_DAYS;
  }
}
