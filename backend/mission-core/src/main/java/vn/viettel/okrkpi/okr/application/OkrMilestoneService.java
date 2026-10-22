package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinCommand;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinOutcome;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneDraft;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneRow;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểu đo mốc của KR (CORE-01-14, BS-03 ADR-33 DC-04, {@code listOkrKrMilestones}, {@code
 * replaceOkrKrMilestones}, {@code achieveOkrKrMilestone}): khai báo mốc có hạn và điều kiện công
 * nhận khi mục tiêu còn nháp, ghi nhận ngày đạt mốc khi mục tiêu đã duyệt, tính số ngày làm việc
 * chậm theo lịch của MOD-08 ({@link WorkingCalendarApi}). Ghi nhận đạt mốc cập nhật tiến độ KR như
 * một check-in (giá trị là số mốc đã đạt, phát EVT-02). Lõi không quy đổi ra tỷ lệ hoàn thành và
 * không có giá trị riêng của hồ sơ khách hàng nào (YC-TK-11).
 */
public class OkrMilestoneService {

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  static final String MOC = "MOC";
  static final int MAX_ITEMS = 50;
  static final int CONDITION_MAX = 1000;
  static final int EVIDENCE_MAX = 200;

  /** Mốc kèm số ngày làm việc chậm: đã đạt thì tính tới ngày đạt, chưa đạt thì tới hôm nay. */
  public record MilestoneView(MilestoneRow row, LocalDate achievedOn, long lateWorkingDays) {}

  /** Danh sách mốc kèm phiên bản KR (ETag). */
  public record Milestones(List<MilestoneView> items, long keyResultVersion) {}

  /** Mốc vừa ghi nhận kèm phiên bản KR sau check-in. */
  public record Achieved(MilestoneView milestone, long keyResultVersion) {}

  private final OkrCheckinRepository keyResults;
  private final OkrMilestoneRepository milestones;
  private final PeriodApi periods;
  private final Supplier<WorkingCalendarApi> calendar;
  private final Supplier<OkrCheckinDelegatePort> delegates;
  private final OkrCheckinService checkins;
  private final Clock clock;

  public OkrMilestoneService(
      OkrCheckinRepository keyResults,
      OkrMilestoneRepository milestones,
      PeriodApi periods,
      Supplier<WorkingCalendarApi> calendar,
      Supplier<OkrCheckinDelegatePort> delegates,
      OkrCheckinService checkins,
      Clock clock) {
    this.keyResults = Objects.requireNonNull(keyResults, "keyResults");
    this.milestones = Objects.requireNonNull(milestones, "milestones");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.delegates = Objects.requireNonNull(delegates, "delegates");
    this.checkins = Objects.requireNonNull(checkins, "checkins");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Thay toàn bộ danh sách mốc của KR kiểu mốc khi mục tiêu còn {@code NHAP}: thứ tự riêng từ 1,
   * hạn không giảm theo thứ tự, hạn mốc cuối không sau hạn KR.
   */
  @Transactional(transactionManager = TX)
  public Milestones replaceMilestones(
      TenantId tenant,
      UUID actor,
      UUID keyResultId,
      long expectedVersion,
      List<MilestoneDraft> drafts) {
    CheckinTarget kr = milestoneKeyResult(tenant, keyResultId);
    if (!actor.equals(kr.owner())) {
      throw new OkrCheckinService.Forbidden();
    }
    if (kr.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, kr.rowVersion());
    }
    if (!"NHAP".equals(kr.objectiveStatus())) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE",
          "Mục tiêu đã trình hoặc đã duyệt nên không sửa mốc tại chỗ; hãy tạo phiên bản điều chỉnh.");
    }
    List<MilestoneDraft> sorted = validate(tenant, kr, drafts);
    requireWritable(tenant, kr, false);
    milestones.replaceMilestones(tenant, keyResultId, sorted, actor);
    long version =
        milestones.touchKeyResult(tenant, kr.periodBucket(), keyResultId, kr.rowVersion(), actor);
    if (version == 0) {
      throw VersionConflictException.of(MODULE, expectedVersion, kr.rowVersion() + 1);
    }
    return new Milestones(milestones(tenant, keyResultId), version);
  }

  /**
   * Ghi nhận đạt mốc (chủ sở hữu KR hoặc người được check-in thay, mục tiêu đã duyệt, kỳ còn mở);
   * lần ghi sau đính chính lần trước. Tiến độ KR cập nhật bằng một check-in đã gửi có giá trị là số
   * mốc đã đạt, mức tự tin giữ như hiện tại của KR (suy luận).
   */
  @Transactional(transactionManager = TX)
  public Achieved recordAchievement(
      TenantId tenant,
      UUID actor,
      UUID keyResultId,
      long expectedVersion,
      UUID milestoneId,
      LocalDate achievedOn,
      String evidenceRef) {
    CheckinTarget kr = milestoneKeyResult(tenant, keyResultId);
    OkrCheckinDelegatePort port = delegates.get();
    if (!actor.equals(kr.owner())
        && (port == null || !port.isDelegate(tenant, keyResultId, actor))) {
      throw new OkrCheckinService.Forbidden();
    }
    if (kr.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, kr.rowVersion());
    }
    if (!"DA_DUYET".equals(kr.objectiveStatus())) {
      throw new BusinessException(
          MODULE + "-409-OBJECTIVE-NOT-APPROVED", "Mục tiêu chưa được phê duyệt.");
    }
    if (achievedOn == null || achievedOn.isAfter(LocalDate.now(clock))) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Ngày đạt mốc không được sau ngày hôm nay.");
    }
    if (evidenceRef != null && evidenceRef.length() > EVIDENCE_MAX) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Tham chiếu minh chứng không vượt quá 200 ký tự.");
    }
    PeriodSnapshot period = requireWritable(tenant, kr, true);
    periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(kr.ownerUnitId()));
    MilestoneRow row =
        milestones
            .markAchieved(
                tenant,
                keyResultId,
                milestoneId,
                achievedOn.atStartOfDay(clock.getZone()).toInstant(),
                evidenceRef == null || evidenceRef.isBlank() ? null : evidenceRef.strip(),
                actor)
            .orElseThrow(OkrCheckinService.NotFound::new);
    long achieved =
        milestones.milestones(tenant, keyResultId).stream()
            .filter(m -> m.achievedAt() != null)
            .count();
    CheckinOutcome outcome =
        checkins.record(
            tenant,
            actor,
            keyResultId,
            "MOC-" + UUID.randomUUID(),
            new CheckinCommand(
                OkrCheckinService.DA_GUI,
                BigDecimal.valueOf(achieved),
                kr.confidence() == null ? "CAO" : kr.confidence(),
                null,
                null,
                null,
                "Đạt mốc " + row.seq(),
                null,
                "WEB",
                true));
    return new Achieved(view(tenant, row), outcome.keyResult().rowVersion());
  }

  /** Danh sách mốc kèm phiên bản KR; KR không đo theo mốc trả danh sách rỗng. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Milestones list(TenantId tenant, UUID keyResultId) {
    CheckinTarget kr =
        keyResults.target(tenant, keyResultId).orElseThrow(OkrCheckinService.NotFound::new);
    return new Milestones(
        MOC.equals(kr.metricType()) ? milestones(tenant, keyResultId) : List.of(), kr.rowVersion());
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
    return new MilestoneView(m, m.achievedAt() == null ? null : until, late);
  }

  private List<MilestoneDraft> validate(
      TenantId tenant, CheckinTarget kr, List<MilestoneDraft> drafts) {
    if (drafts == null || drafts.isEmpty() || drafts.size() > MAX_ITEMS) {
      throw invalid("Khai báo từ 1 đến 50 mốc.");
    }
    Set<Integer> seen = new HashSet<>();
    for (MilestoneDraft d : drafts) {
      if (d.seq() < 1 || !seen.add(d.seq()) || d.dueDate() == null) {
        throw invalid("Mỗi mốc cần thứ tự riêng từ 1 và hạn hoàn thành.");
      }
      if (d.recognitionCondition() != null && d.recognitionCondition().length() > CONDITION_MAX) {
        throw invalid("Điều kiện công nhận không vượt quá 1.000 ký tự.");
      }
    }
    Set<UUID> existing = new HashSet<>();
    milestones.milestones(tenant, kr.keyResultId()).forEach(m -> existing.add(m.id()));
    List<MilestoneDraft> sorted = new ArrayList<>(drafts);
    sorted.sort(Comparator.comparingInt(MilestoneDraft::seq));
    LocalDate previous = null;
    for (MilestoneDraft d : sorted) {
      if (d.id() != null && !existing.contains(d.id())) {
        throw invalid("Có mốc không thuộc kết quả then chốt này.");
      }
      if (previous != null && d.dueDate().isBefore(previous)) {
        throw invalid("Hạn của mốc sau không được trước hạn của mốc trước.");
      }
      previous = d.dueDate();
    }
    LocalDate due =
        milestones.keyResultDueDate(tenant, kr.periodBucket(), kr.keyResultId()).orElse(null);
    if (due != null && previous.isAfter(due)) {
      throw invalid("Hạn của mốc cuối không được sau hạn của kết quả then chốt.");
    }
    return List.copyOf(sorted);
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(MODULE + "-422-VALIDATION", message);
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
          MODULE + "-422-NOT-MILESTONE-KR", "Kết quả then chốt này không đo theo mốc.");
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
