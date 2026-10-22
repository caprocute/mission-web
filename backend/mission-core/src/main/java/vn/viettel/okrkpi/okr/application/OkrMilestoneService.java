package vn.viettel.okrkpi.okr.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneDraft;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneRow;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểu đo mốc của KR (CORE-01-14, BS-03 ADR-33 DC-04): khai báo mốc có hạn và điều kiện công nhận
 * khi mục tiêu còn nháp, ghi nhận ngày đạt mốc khi mục tiêu đã duyệt, tính số ngày làm việc chậm
 * theo lịch của MOD-08 ({@link WorkingCalendarApi}). Lõi không quy đổi ra tỷ lệ hoàn thành và không
 * có giá trị riêng của hồ sơ khách hàng nào (YC-TK-11).
 */
public class OkrMilestoneService {

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  static final String MOC = "MOC";
  static final int CONDITION_MAX = 1000;
  static final int EVIDENCE_MAX = 500;

  /** Mốc kèm số ngày làm việc chậm: đã đạt thì tính tới ngày đạt, chưa đạt thì tới hôm nay. */
  public record MilestoneView(MilestoneRow row, long lateWorkingDays) {}

  private final OkrCheckinRepository keyResults;
  private final OkrMilestoneRepository milestones;
  private final PeriodApi periods;
  private final Supplier<WorkingCalendarApi> calendar;
  private final Supplier<OkrCheckinDelegatePort> delegates;
  private final Clock clock;

  public OkrMilestoneService(
      OkrCheckinRepository keyResults,
      OkrMilestoneRepository milestones,
      PeriodApi periods,
      Supplier<WorkingCalendarApi> calendar,
      Supplier<OkrCheckinDelegatePort> delegates,
      Clock clock) {
    this.keyResults = Objects.requireNonNull(keyResults, "keyResults");
    this.milestones = Objects.requireNonNull(milestones, "milestones");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.delegates = Objects.requireNonNull(delegates, "delegates");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Đặt lại danh sách mốc chưa đạt của KR kiểu mốc; chỉ khi mục tiêu còn {@code NHAP}. */
  @Transactional(transactionManager = TX)
  public List<MilestoneView> replaceMilestones(
      TenantId tenant, UUID actor, UUID keyResultId, List<MilestoneDraft> drafts) {
    CheckinTarget kr = milestoneKeyResult(tenant, keyResultId);
    if (!actor.equals(kr.owner())) {
      throw new OkrCheckinService.Forbidden();
    }
    if (!"NHAP".equals(kr.objectiveStatus())) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE",
          "Mục tiêu đã trình hoặc đã duyệt nên không sửa mốc tại chỗ; hãy tạo phiên bản điều chỉnh.");
    }
    Set<Integer> seen = new HashSet<>();
    for (MilestoneDraft d : drafts) {
      if (d.seq() < 1 || !seen.add(d.seq()) || d.dueDate() == null) {
        throw new BusinessException(
            MODULE + "-422-VALIDATION", "Mỗi mốc cần thứ tự riêng từ 1 và hạn hoàn thành.");
      }
      if (d.recognitionCondition() != null && d.recognitionCondition().length() > CONDITION_MAX) {
        throw new BusinessException(
            MODULE + "-422-VALIDATION", "Điều kiện công nhận không vượt quá 1.000 ký tự.");
      }
    }
    requireWritable(tenant, kr, false);
    milestones.replaceMilestones(tenant, keyResultId, List.copyOf(drafts), actor);
    return milestones(tenant, keyResultId);
  }

  /**
   * Ghi nhận đạt mốc {@code seq} (chủ sở hữu KR hoặc người được check-in thay, mục tiêu đã duyệt,
   * kỳ còn mở). Ghi lần hai giữ nguyên ngày đạt lần đầu.
   */
  @Transactional(transactionManager = TX)
  public MilestoneView recordAchievement(
      TenantId tenant,
      UUID actor,
      UUID keyResultId,
      int seq,
      Instant achievedAt,
      String evidenceRef) {
    CheckinTarget kr = milestoneKeyResult(tenant, keyResultId);
    OkrCheckinDelegatePort port = delegates.get();
    if (!actor.equals(kr.owner())
        && (port == null || !port.isDelegate(tenant, keyResultId, actor))) {
      throw new OkrCheckinService.Forbidden();
    }
    if (!"DA_DUYET".equals(kr.objectiveStatus())) {
      throw new BusinessException(
          MODULE + "-409-OBJECTIVE-NOT-APPROVED", "Mục tiêu chưa được phê duyệt.");
    }
    if (evidenceRef != null && evidenceRef.length() > EVIDENCE_MAX) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Tham chiếu minh chứng không vượt quá 500 ký tự.");
    }
    PeriodSnapshot period = requireWritable(tenant, kr, true);
    periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(kr.ownerUnitId()));
    Instant at = achievedAt == null ? clock.instant() : achievedAt;
    MilestoneRow row =
        milestones
            .markAchieved(tenant, keyResultId, seq, at, evidenceRef, actor)
            .orElseThrow(OkrCheckinService.NotFound::new);
    return view(tenant, row);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<MilestoneView> milestones(TenantId tenant, UUID keyResultId) {
    return milestones.milestones(tenant, keyResultId).stream().map(m -> view(tenant, m)).toList();
  }

  /** Số ngày làm việc trong {@code (hạn, ngày đạt hoặc hôm nay]}. */
  MilestoneView view(TenantId tenant, MilestoneRow m) {
    ZoneId zone = clock.getZone();
    LocalDate until =
        m.achievedAt() == null ? LocalDate.now(clock) : m.achievedAt().atZone(zone).toLocalDate();
    WorkingCalendarApi api = calendar.get();
    long late =
        !until.isAfter(m.dueDate())
            ? 0
            : api != null
                ? api.countWorkingDays(tenant, m.dueDate(), until)
                : weekdaysBetween(m.dueDate(), until);
    return new MilestoneView(m, late);
  }

  private static long weekdaysBetween(LocalDate fromExclusive, LocalDate toInclusive) {
    long n = 0;
    for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
      if (d.getDayOfWeek().getValue() <= 5) {
        n++;
      }
    }
    return n;
  }

  private CheckinTarget milestoneKeyResult(TenantId tenant, UUID keyResultId) {
    CheckinTarget kr =
        keyResults.target(tenant, keyResultId).orElseThrow(OkrCheckinService.NotFound::new);
    if (!MOC.equals(kr.metricType())) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Kết quả then chốt này không đo theo mốc.");
    }
    return kr;
  }

  private PeriodSnapshot requireWritable(TenantId tenant, CheckinTarget kr, boolean open) {
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(kr.periodId()))
            .orElseThrow(() -> new IllegalStateException("KR trỏ tới kỳ không tồn tại"));
    try {
      if (open && period.status() != PeriodStatus.MO) {
        throw new PeriodLockedException(period.id(), period.name(), MODULE);
      }
      periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(kr.ownerUnitId()));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    return period;
  }
}
