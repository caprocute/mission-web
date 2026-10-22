package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.application.OkrMilestoneService.MilestoneView;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-01-14: số ngày làm việc chậm của mốc theo lịch nghỉ (BS-03 DC-04). */
class OkrMilestoneServiceTest {

  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
  static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  /** Lịch giả: nghỉ thứ Bảy, Chủ nhật và các ngày lễ đã cho. */
  record FakeCalendar(Set<LocalDate> holidays) implements WorkingCalendarApi {
    @Override
    public boolean isWorkingDay(TenantId tenantId, LocalDate date) {
      return date.getDayOfWeek().getValue() <= 5 && !holidays.contains(date);
    }

    @Override
    public LocalDate addWorkingDays(TenantId tenantId, LocalDate from, int n) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countWorkingDays(TenantId tenantId, LocalDate fromExclusive, LocalDate to) {
      long n = 0;
      for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
        if (isWorkingDay(tenantId, d)) {
          n++;
        }
      }
      return n;
    }

    @Override
    public WorkingCalendar celCalendar(TenantId tenantId) {
      throw new UnsupportedOperationException();
    }
  }

  private static OkrMilestoneService service(WorkingCalendarApi calendar, LocalDate today) {
    Clock clock = Clock.fixed(today.atTime(9, 0).atZone(VN).toInstant(), VN);
    return new OkrMilestoneService(
        mock(OkrCheckinRepository.class),
        mock(OkrMilestoneRepository.class),
        mock(PeriodApi.class),
        () -> calendar,
        () -> null,
        clock);
  }

  private static MilestoneRow row(LocalDate due, LocalDate achieved) {
    Instant at =
        achieved == null ? null : ZonedDateTime.of(achieved.atTime(16, 30), VN).toInstant();
    return new MilestoneRow(UUID.randomUUID(), 1, due, "Có văn bản ban hành", at, null, 0);
  }

  @Test
  void datSauHanQuaKyNghiLeChiDemNgayLamViec() {
    // Hạn thứ Năm 29/4/2027; nghỉ lễ 30/4 và 1/5; đạt thứ Ba 4/5: chậm thứ Hai 3/5 và thứ Ba 4/5.
    FakeCalendar calendar =
        new FakeCalendar(Set.of(LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 1)));
    OkrMilestoneService service = service(calendar, LocalDate.of(2027, 5, 10));
    MilestoneView late =
        service.view(TENANT, row(LocalDate.of(2027, 4, 29), LocalDate.of(2027, 5, 4)));
    assertThat(late.lateWorkingDays()).isEqualTo(2);
  }

  @Test
  void datDungHanHoacTruocHanKhongCham() {
    OkrMilestoneService service = service(new FakeCalendar(Set.of()), LocalDate.of(2027, 5, 10));
    assertThat(
            service
                .view(TENANT, row(LocalDate.of(2027, 4, 29), LocalDate.of(2027, 4, 29)))
                .lateWorkingDays())
        .isZero();
    assertThat(
            service
                .view(TENANT, row(LocalDate.of(2027, 4, 29), LocalDate.of(2027, 4, 20)))
                .lateWorkingDays())
        .isZero();
  }

  @Test
  void chuaDatThiDemToiHomNay() {
    // Hạn thứ Sáu 7/5/2027, hôm nay thứ Ba 11/5: chậm thứ Hai 10/5 và thứ Ba 11/5.
    OkrMilestoneService service = service(new FakeCalendar(Set.of()), LocalDate.of(2027, 5, 11));
    assertThat(service.view(TENANT, row(LocalDate.of(2027, 5, 7), null)).lateWorkingDays())
        .isEqualTo(2);
  }
}
