package vn.viettel.okrkpi.okr.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult;
import vn.viettel.okrkpi.okr.application.port.OkrReminderEvents;
import vn.viettel.okrkpi.okr.domain.CheckinCycle;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nhắc check-in OKR chạy nền (QT-03 bước 1, BR-03-08, CORE-01-11). Vào ngày nhắc của tuần (thứ Năm,
 * dời sang ngày làm việc kế tiếp nếu nghỉ), KR của mục tiêu đã duyệt trong kỳ đang mở chưa có
 * check-in đã gửi trong chu kỳ nhận EVT-21 tới chủ sở hữu và người check-in thay; KR bỏ trống hai
 * chu kỳ liền trước thì người đứng đầu đơn vị nhận thêm một EVT-21. Mỗi trang KR một giao dịch,
 * khoá giữ chỗ theo (KR, chu kỳ) ghi cùng giao dịch với sự kiện nên nhiều bản sao chạy cùng lúc hay
 * chạy lại sau lỗi vẫn chỉ sinh một bộ sự kiện. Từ ngày nhắc tới Chủ nhật các lần chạy sau tiếp tục
 * phần chưa gửi. Nhắc hạn duyệt OKR (MSG-02-12) do timer {@code remindAfter} của {@code duyet-okr}
 * (DV-06-03) đảm nhận nên không dựng tác vụ trùng ở đây.
 */
public class OkrCheckinReminders {

  /** Số chu kỳ bỏ trống liền nhau để báo người đứng đầu (BR-03-08). */
  static final int OVERDUE_CYCLES = 2;

  private final OkrCheckinReminderRepository repository;
  private final PeriodApi periods;
  private final Supplier<WorkingCalendarApi> calendar;
  private final Supplier<OrgDirectoryApi> org;
  private final OkrReminderEvents events;
  private final TransactionOperations tx;
  private final Clock clock;
  private final int pageSize;

  public OkrCheckinReminders(
      OkrCheckinReminderRepository repository,
      PeriodApi periods,
      Supplier<WorkingCalendarApi> calendar,
      Supplier<OrgDirectoryApi> org,
      OkrReminderEvents events,
      TransactionOperations tx,
      Clock clock,
      int pageSize) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.org = Objects.requireNonNull(org, "org");
    this.events = Objects.requireNonNull(events, "events");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize");
    }
    this.pageSize = pageSize;
  }

  /** Chạy cho một tenant; trả số EVT-21 đã ghi. */
  public int run(TenantId tenant) {
    LocalDate today = LocalDate.now(clock);
    PeriodSnapshot period =
        periods
            .findCurrent(tenant)
            .filter(PeriodSnapshot::isOpen)
            .filter(p -> !today.isBefore(p.startDate()) && !today.isAfter(p.endDate()))
            .orElse(null);
    if (period == null) {
      return 0;
    }
    WorkingCalendarApi cal = calendar.get();
    Predicate<LocalDate> working = d -> cal == null || cal.isWorkingDay(tenant, d);
    LocalDate reminderDay = CheckinCycle.reminderDay(today, working);
    if (reminderDay == null || today.isBefore(reminderDay)) {
      return 0;
    }
    LocalDate thursday = CheckinCycle.thursdayOf(today);
    int sent = 0;
    UUID after = null;
    while (true) {
      UUID from = after;
      Page page =
          tx.execute(
              s -> {
                List<DueKeyResult> items =
                    repository.dueKeyResults(tenant, period.id().value(), today, from, pageSize);
                int n = 0;
                for (DueKeyResult kr : items) {
                  n += remind(tenant, kr, thursday, period.startDate(), today);
                }
                return new Page(items, n);
              });
      if (page == null) {
        return sent;
      }
      sent += page.sent();
      if (page.items().size() < pageSize) {
        return sent;
      }
      after = page.items().getLast().id();
    }
  }

  private record Page(List<DueKeyResult> items, int sent) {}

  private int remind(
      TenantId tenant,
      DueKeyResult kr,
      LocalDate thursday,
      LocalDate periodStart,
      LocalDate today) {
    String frequency = kr.frequency();
    if (!CheckinCycle.remindWeek(frequency, thursday, periodStart)) {
      return 0;
    }
    CheckinCycle cycle = CheckinCycle.of(frequency, thursday, periodStart);
    LocalDate last = dateOf(kr.lastSubmittedAt());
    if (last != null && !last.isBefore(cycle.start())) {
      return 0;
    }
    int sent = 0;
    if (repository.claimNotice(tenant, "nhac-check-in:" + kr.id() + ":" + cycle.start())) {
      Set<UUID> to = new LinkedHashSet<>();
      to.add(kr.owner());
      if (kr.delegate() != null) {
        to.add(kr.delegate());
      }
      events.checkinDue(tenant, kr, new ArrayList<>(to), cycle.end());
      sent++;
    }
    CheckinCycle earliest = cycle;
    for (int i = 0; i < OVERDUE_CYCLES; i++) {
      earliest = earliest.previous(frequency, periodStart);
    }
    LocalDate since = dateOf(kr.activeSince());
    boolean overdue =
        since != null
            && !since.isAfter(earliest.start())
            && (last == null || last.isBefore(earliest.start()));
    if (overdue) {
      OrgDirectoryApi directory = org.get();
      List<UUID> heads =
          directory == null ? List.of() : directory.headsOf(tenant, kr.ownerUnitId(), today);
      if (!heads.isEmpty()
          && repository.claimNotice(tenant, "qua-han-check-in:" + kr.id() + ":" + cycle.start())) {
        events.checkinOverdue(tenant, kr, heads, OVERDUE_CYCLES);
        sent++;
      }
    }
    return sent;
  }

  private LocalDate dateOf(Instant at) {
    return at == null ? null : LocalDate.ofInstant(at, clock.getZone());
  }
}
