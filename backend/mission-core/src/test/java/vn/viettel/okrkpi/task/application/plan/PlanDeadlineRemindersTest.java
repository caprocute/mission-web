package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;
import vn.viettel.okrkpi.config.api.HolidayCalendarChangedEvent;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanDeadlineNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanCommand;
import vn.viettel.okrkpi.task.domain.plan.PlanTransition;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStateMachine;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** CORE-02-07: nhắc trước hạn và báo quá hạn với đồng hồ giả lập. */
class PlanDeadlineRemindersTest {

  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a02-0000-7000-8000-0000000000f1"));
  private static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-0000000000f2");
  private static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-0000000000f3");
  private static final UUID AUTHOR = UUID.fromString("01927a02-0000-7000-8000-0000000000f4");
  private static final PeriodSnapshot Q4 =
      new PeriodSnapshot(
          PeriodId.of(UUID.fromString("01927a02-0000-7000-8000-0000000000f5")),
          TENANT,
          "Q4-2026",
          "Quý IV/2026",
          LocalDate.parse("2026-10-01"),
          LocalDate.parse("2026-12-31"),
          PeriodStatus.MO);

  /** Đồng hồ đổi được ngày giữa các lần chạy. */
  static final class MutableClock extends Clock {
    Instant now;

    void day(String date) {
      now = LocalDate.parse(date).atTime(8, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Lịch cuối tuần nghỉ cộng ngày nghỉ đổi được (CORE-08-04 thật ở kiểm thử tích hợp). */
  static final class Calendar implements WorkingCalendarApi {
    final Set<LocalDate> off = new HashSet<>();

    @Override
    public boolean isWorkingDay(TenantId t, LocalDate d) {
      return !off.contains(d)
          && d.getDayOfWeek() != DayOfWeek.SATURDAY
          && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    @Override
    public LocalDate addWorkingDays(TenantId t, LocalDate from, int n) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countWorkingDays(TenantId t, LocalDate from, LocalDate to) {
      long n = 0;
      for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
        n += isWorkingDay(t, d) ? 1 : 0;
      }
      return n;
    }

    @Override
    public WorkingCalendar celCalendar(TenantId t) {
      throw new UnsupportedOperationException();
    }
  }

  record Sent(String kind, List<UUID> to, LocalDate deadline) {}

  private final MutableClock clock = new MutableClock();
  private final Calendar calendar = new Calendar();
  private final PlanDeadlines deadlines = new PlanDeadlines(calendar, null, clock);
  private final List<Sent> sent = new ArrayList<>();
  private final Set<String> claims = new HashSet<>();
  private final List<PendingPlan> pending =
      new ArrayList<>(
          List.of(new PendingPlan(UUID.randomUUID(), UNIT, UUID.randomUUID(), AUTHOR, 3)));

  private final PlanRepository repo =
      (PlanRepository)
          Proxy.newProxyInstance(
              PlanRepository.class.getClassLoader(),
              new Class<?>[] {PlanRepository.class},
              (proxy, m, args) ->
                  switch (m.getName()) {
                    case "pendingApproval" -> List.copyOf(pending);
                    // Khoá chính idempotency_key có ngày tạo: trùng trong ngày mới bị chặn.
                    case "claimNotice" -> claims.add(deadlines.today() + "|" + args[1]);
                    default -> throw new UnsupportedOperationException(m.getName());
                  });

  private final PeriodApi periods =
      (PeriodApi)
          Proxy.newProxyInstance(
              PeriodApi.class.getClassLoader(),
              new Class<?>[] {PeriodApi.class},
              (proxy, m, args) -> m.getName().equals("findCurrent") ? Optional.of(Q4) : null);

  private final OrgDirectoryApi org =
      (OrgDirectoryApi)
          Proxy.newProxyInstance(
              OrgDirectoryApi.class.getClassLoader(),
              new Class<?>[] {OrgDirectoryApi.class},
              (proxy, m, args) -> m.getName().equals("headsOf") ? List.of(HEAD) : null);

  private final PlanDeadlineNotices notices =
      new PlanDeadlineNotices() {
        @Override
        public void remind(TenantId t, PendingPlan p, String name, LocalDate d, List<UUID> to) {
          sent.add(new Sent("nhac", to, d));
        }

        @Override
        public void overdue(TenantId t, PendingPlan p, String name, LocalDate d, List<UUID> to) {
          sent.add(new Sent("qua-han", to, d));
        }
      };

  private final PlanDeadlineReminders reminders =
      new PlanDeadlineReminders(repo, periods, calendar, deadlines, notices, org);

  /** Chạy cả tháng, mỗi ngày ba lần như lịch cron. */
  private void runOctober() {
    for (LocalDate d = LocalDate.parse("2026-10-01");
        d.isBefore(LocalDate.parse("2026-11-01"));
        d = d.plusDays(1)) {
      clock.day(d.toString());
      for (int i = 0; i < 3; i++) {
        reminders.run(TENANT);
      }
    }
  }

  @Test
  void nhacTruocHanVaBaoQuaHanMoiMocDungMotLan() {
    runOctober();
    LocalDate deadline = LocalDate.parse("2026-10-09");
    assertThat(sent)
        .containsExactly(
            new Sent("nhac", List.of(AUTHOR, HEAD), deadline),
            new Sent("qua-han", List.of(HEAD), deadline));
  }

  @Test
  void nhacVaoNgayLamViecConBaNgayVaBaoVaoNgayLamViecDauSauHan() {
    clock.day("2026-10-05");
    assertThat(reminders.run(TENANT)).isZero();
    clock.day("2026-10-06");
    assertThat(reminders.run(TENANT)).isEqualTo(1);
    clock.day("2026-10-10"); // thứ Bảy
    assertThat(reminders.run(TENANT)).isZero();
    clock.day("2026-10-12");
    assertThat(reminders.run(TENANT)).isEqualTo(1);
    assertThat(deadlines.workingDaysTo(TENANT, LocalDate.parse("2026-10-09"))).isEqualTo(-1);
  }

  @Test
  void keHoachDaDuyetKhongDuocNhac() {
    pending.clear();
    runOctober();
    assertThat(sent).isEmpty();
  }

  @Test
  void duyetSauHanVanThanhCongVaGhiDauDuyetMuon() {
    clock.day("2026-10-15");
    assertThat(
            PlanVersionStateMachine.apply(PlanVersionStatus.CHO_DUYET, PlanCommand.APPROVE, true))
        .isInstanceOf(PlanTransition.Accepted.class);
    VersionRow approvedLate = version(PlanVersionStatus.DA_DUYET, "2026-10-15T03:00:00Z");
    VersionRow approvedOnTime = version(PlanVersionStatus.DA_DUYET, "2026-10-09T09:00:00Z");
    assertThat(deadlines.late(TENANT, Q4, approvedLate)).isTrue();
    assertThat(deadlines.late(TENANT, Q4, approvedOnTime)).isFalse();
    assertThat(deadlines.late(TENANT, Q4, version(PlanVersionStatus.NHAP, null))).isTrue();
  }

  @Test
  void lichNghiDoiThiTinhLaiHan() {
    assertThat(deadlines.deadlineOf(TENANT, Q4)).isEqualTo(LocalDate.parse("2026-10-09"));
    calendar.off.add(LocalDate.parse("2026-10-09"));
    assertThat(deadlines.deadlineOf(TENANT, Q4)).isEqualTo(LocalDate.parse("2026-10-09"));
    deadlines.evict(new HolidayCalendarChangedEvent(TENANT, 2026).tenantId());
    assertThat(deadlines.deadlineOf(TENANT, Q4)).isEqualTo(LocalDate.parse("2026-10-08"));
  }

  private static VersionRow version(PlanVersionStatus status, String approvedAt) {
    return new VersionRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        status,
        status == PlanVersionStatus.DA_DUYET,
        null,
        approvedAt == null ? null : Instant.parse(approvedAt),
        null,
        null,
        null,
        0,
        1);
  }
}
