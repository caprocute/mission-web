package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult;
import vn.viettel.okrkpi.okr.application.port.OkrReminderEvents;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị nhắc check-in chạy nền (CORE-01-11) với kho giả lập và đồng hồ cố định. */
class OkrCheckinRemindersTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID OWNER = UUID.randomUUID();
  static final UUID DELEGATE = UUID.randomUUID();
  static final UUID HEAD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Instant APPROVED = Instant.parse("2026-12-20T03:00:00Z");

  private final OkrCheckinReminderRepository repo = mock(OkrCheckinReminderRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final WorkingCalendarApi calendar = mock(WorkingCalendarApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final OkrReminderEvents events = mock(OkrReminderEvents.class);
  private final Set<String> claimed = new HashSet<>();

  private OkrCheckinReminders reminders(String day, WorkingCalendarApi cal) {
    return new OkrCheckinReminders(
        repo,
        periods,
        () -> cal,
        () -> org,
        events,
        TransactionOperations.withoutTransaction(),
        Clock.fixed(LocalDate.parse(day).atTime(7, 0).atZone(VN).toInstant(), VN),
        2);
  }

  static DueKeyResult kr(String frequency, Instant last, UUID delegate) {
    return new DueKeyResult(
        UUID.randomUUID(),
        "KR 1.1",
        frequency,
        UUID.randomUUID(),
        UNIT,
        OWNER,
        delegate,
        APPROVED,
        last,
        3);
  }

  @BeforeEach
  void setUp() {
    when(periods.findCurrent(TENANT))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "Q1",
                    "Quý I",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 3, 31),
                    PeriodStatus.MO)));
    when(repo.claimNotice(eq(TENANT), any())).thenAnswer(inv -> claimed.add(inv.getArgument(1)));
    when(org.headsOf(eq(TENANT), eq(UNIT), any())).thenReturn(List.of(HEAD));
    when(calendar.isWorkingDay(eq(TENANT), any())).thenReturn(true);
  }

  @Test
  void nhacTuanTheoTrangVaBaoQuaHan() {
    DueKeyResult never = kr("TUAN", null, DELEGATE);
    DueKeyResult done = kr("TUAN", Instant.parse("2027-02-09T02:00:00Z"), null);
    DueKeyResult monthly = kr("THANG", null, null);
    DueKeyResult recent = kr("TUAN", Instant.parse("2027-02-02T02:00:00Z"), null);
    when(repo.dueKeyResults(eq(TENANT), eq(PERIOD), any(), eq(null), eq(2)))
        .thenReturn(List.of(never, done));
    when(repo.dueKeyResults(eq(TENANT), eq(PERIOD), any(), eq(done.id()), eq(2)))
        .thenReturn(List.of(monthly, recent));
    when(repo.dueKeyResults(eq(TENANT), eq(PERIOD), any(), eq(recent.id()), eq(2)))
        .thenReturn(List.of());
    OkrCheckinReminders r = reminders("2027-02-11", calendar);
    assertThat(r.run(TENANT)).isEqualTo(3);
    verify(events).checkinDue(TENANT, never, List.of(OWNER, DELEGATE), LocalDate.of(2027, 2, 14));
    verify(events).checkinOverdue(TENANT, never, List.of(HEAD), 2);
    verify(events).checkinDue(TENANT, recent, List.of(OWNER), LocalDate.of(2027, 2, 14));
    verify(events, never()).checkinOverdue(eq(TENANT), eq(recent), any(), any(int.class));
    // Chạy lại: khoá đã có nên không ghi thêm.
    assertThat(r.run(TENANT)).isZero();
  }

  @Test
  void ngayNghiDoiNgayNhacVaNgoaiKy() {
    DueKeyResult k = kr("TUAN", null, null);
    when(repo.dueKeyResults(eq(TENANT), eq(PERIOD), any(), eq(null), eq(2))).thenReturn(List.of(k));
    when(calendar.isWorkingDay(TENANT, LocalDate.of(2027, 2, 11))).thenReturn(false);
    assertThat(reminders("2027-02-11", calendar).run(TENANT)).isZero();
    assertThat(reminders("2027-02-12", calendar).run(TENANT)).isEqualTo(2);
    // Cả cuối tuần nghỉ: không nhắc.
    when(calendar.isWorkingDay(eq(TENANT), any())).thenReturn(false);
    assertThat(reminders("2027-02-19", calendar).run(TENANT)).isZero();
    // Trước ngày nhắc hoặc ngoài kỳ, kỳ không mở.
    assertThat(reminders("2027-02-15", null).run(TENANT)).isZero();
    assertThat(reminders("2027-04-08", null).run(TENANT)).isZero();
    when(periods.findCurrent(TENANT)).thenReturn(Optional.empty());
    assertThat(reminders("2027-02-18", null).run(TENANT)).isZero();
  }

  @Test
  void khongCoNguoiDungDauThiChiNhacChuSoHuu() {
    DueKeyResult k = kr("HAI_TUAN", null, null);
    when(repo.dueKeyResults(eq(TENANT), eq(PERIOD), any(), eq(null), eq(2))).thenReturn(List.of(k));
    OkrCheckinReminders noOrg =
        new OkrCheckinReminders(
            repo,
            periods,
            () -> null,
            () -> null,
            events,
            TransactionOperations.withoutTransaction(),
            Clock.fixed(LocalDate.of(2027, 2, 4).atTime(7, 0).atZone(VN).toInstant(), VN),
            2);
    assertThat(noOrg.run(TENANT)).isEqualTo(1);
    verify(events, never()).checkinOverdue(any(), any(), any(), any(int.class));
    assertThatThrownBy(
            () ->
                new OkrCheckinReminders(
                    repo,
                    periods,
                    () -> null,
                    () -> null,
                    events,
                    TransactionOperations.withoutTransaction(),
                    Clock.systemUTC(),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
