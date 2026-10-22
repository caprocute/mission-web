package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.HolidayCalendarChangedEvent;
import vn.viettel.okrkpi.config.application.port.HolidayCalendarRepository;
import vn.viettel.okrkpi.config.domain.HolidayDay;
import vn.viettel.okrkpi.config.domain.HolidayDayKind;
import vn.viettel.okrkpi.config.domain.HolidayScope;
import vn.viettel.okrkpi.config.domain.NationalHolidays;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-04: gộp lịch quốc gia với lịch riêng, ghi có kiểm phiên bản, đệm và sự kiện. */
class HolidayCalendarServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID ACTOR = UUID.randomUUID();
  private static final LocalDate SAT = LocalDate.parse("2026-12-26");
  private static final LocalDate TET = LocalDate.parse("2026-02-16");

  private final InMemory repo = new InMemory();
  private final List<Object> events = new ArrayList<>();
  private final HolidayCalendarService service =
      new HolidayCalendarService(
          repo, events::add, TransactionOperations.withoutTransaction(), Duration.ofMinutes(1));

  @Test
  void nationalCalendarIsUsedUntilTenantOverrides() {
    assertThat(service.isWorkingDay(TENANT, TET)).isFalse();
    assertThat(service.calendarYear(TENANT, 2026).workingDaysByMonth()[1]).isEqualTo(15);
    assertThat(service.days(TENANT, 2026, HolidayScope.QUOC_GIA)).hasSize(17);
    assertThat(service.days(TENANT, 2026, HolidayScope.TENANT)).isEmpty();

    LocalDate nationalMakeUp = LocalDate.parse("2026-01-10");
    assertThat(service.isWorkingDay(TENANT, nationalMakeUp)).isTrue();
    HolidayDay override =
        service.create(TENANT, ACTOR, nationalMakeUp, HolidayDayKind.NGHI_BU, " Nghỉ ");
    assertThat(override.name()).isEqualTo("Nghỉ");
    assertThat(service.isWorkingDay(TENANT, nationalMakeUp)).isFalse();
    assertThat(service.days(TENANT, 2026, null)).hasSize(17);
    assertThat(events).containsExactly(new HolidayCalendarChangedEvent(TENANT, 2026));
  }

  @Test
  void createUpdateDeleteWithVersionChecksAndCacheRefresh() {
    long before = service.countWorkingDays(TENANT, SAT.minusDays(1), SAT);
    assertThat(before).isZero();
    HolidayDay day =
        service.create(TENANT, ACTOR, SAT, HolidayDayKind.LAM_BU, "Làm bù cho ngày 02/01/2027");
    assertThat(service.countWorkingDays(TENANT, SAT.minusDays(1), SAT)).isEqualTo(1);
    assertThat(service.addWorkingDays(TENANT, SAT.minusDays(1), 1)).isEqualTo(SAT);
    long v1 = service.calendarYear(TENANT, 2026).version();

    assertThatThrownBy(() -> service.create(TENANT, ACTOR, SAT, HolidayDayKind.LAM_BU, "x"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("CONFIG-409-DATE-EXISTS");
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT, ACTOR, LocalDate.parse("2026-12-28"), HolidayDayKind.LAM_BU, "x"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");

    assertThatThrownBy(
            () -> service.update(TENANT, ACTOR, day.id(), 5, SAT, HolidayDayKind.NGHI_LE, "x"))
        .isInstanceOf(VersionConflictException.class);
    HolidayDay updated =
        service.update(TENANT, ACTOR, day.id(), 0, SAT, HolidayDayKind.NGHI_LE, "Nghỉ");
    assertThat(updated.rowVersion()).isEqualTo(1);
    assertThat(service.isWorkingDay(TENANT, SAT)).isFalse();
    assertThat(service.calendarYear(TENANT, 2026).version()).isNotEqualTo(v1);

    HolidayDay moved =
        service.update(
            TENANT,
            ACTOR,
            day.id(),
            1,
            LocalDate.parse("2027-01-02"),
            HolidayDayKind.LAM_BU,
            "Làm bù");
    assertThat(moved.date().getYear()).isEqualTo(2027);
    repo.failNextWrite = true;
    assertThatThrownBy(
            () -> service.update(TENANT, ACTOR, day.id(), 2, SAT, HolidayDayKind.LAM_BU, "x"))
        .isInstanceOf(VersionConflictException.class);

    assertThatThrownBy(() -> service.delete(TENANT, day.id(), 0))
        .isInstanceOf(VersionConflictException.class);
    repo.failNextWrite = true;
    assertThatThrownBy(() -> service.delete(TENANT, day.id(), 2))
        .isInstanceOf(VersionConflictException.class);
    service.delete(TENANT, day.id(), 2);
    assertThat(service.days(TENANT, 2027, HolidayScope.TENANT)).isEmpty();
    assertThatThrownBy(() -> service.delete(TENANT, day.id(), 3))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(events).hasSize(5);
  }

  @Test
  void updateToExistingDateIsConflict() {
    service.create(TENANT, ACTOR, SAT, HolidayDayKind.LAM_BU, "a");
    HolidayDay other = service.create(TENANT, ACTOR, SAT.plusDays(1), HolidayDayKind.LAM_BU, "b");
    assertThatThrownBy(
            () -> service.update(TENANT, ACTOR, other.id(), 0, SAT, HolidayDayKind.LAM_BU, "b"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("CONFIG-409-DATE-EXISTS");
  }

  @Test
  void nationalDaysAreReadOnly() {
    UUID builtIn = NationalHolidays.idOf(TET);
    assertThatThrownBy(() -> service.delete(TENANT, builtIn, 0))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-403-OUT-OF-SCOPE");
    HolidayDay seeded =
        new HolidayDay(
            UUID.randomUUID(),
            LocalDate.parse("2027-01-01"),
            HolidayDayKind.NGHI_LE,
            "Tết Dương lịch",
            HolidayScope.QUOC_GIA,
            0);
    repo.insert(TENANT, seeded, ACTOR);
    service.evict(TENANT);
    assertThat(service.isWorkingDay(TENANT, seeded.date())).isFalse();
    assertThatThrownBy(
            () ->
                service.update(
                    TENANT, ACTOR, seeded.id(), 0, seeded.date(), HolidayDayKind.NGHI_BU, "x"))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-403-OUT-OF-SCOPE");
  }

  @Test
  void cacheLoadsOncePerTenantAndCelCalendarAgrees() {
    service.isWorkingDay(TENANT, TET);
    service.isWorkingDay(TENANT, SAT);
    assertThat(repo.loads).hasValue(1);
    assertThat(service.celCalendar(TENANT).countWorkingDays(TET.minusDays(3), TET.plusDays(7)))
        .isEqualTo(service.countWorkingDays(TENANT, TET.minusDays(3), TET.plusDays(7)));
    assertThatThrownBy(() -> service.calendarYear(TENANT, 2019))
        .extracting(e -> ((BusinessException) e).errorCode().code())
        .isEqualTo("COMMON-422-VALIDATION");
  }

  /** Kho trong bộ nhớ giữ ràng buộc ngày duy nhất của tenant. */
  static final class InMemory implements HolidayCalendarRepository {
    final Map<UUID, HolidayDay> rows = new LinkedHashMap<>();
    final AtomicInteger loads = new AtomicInteger();
    boolean failNextWrite;

    @Override
    public void insert(TenantId tenantId, HolidayDay day, UUID actorId) {
      if (rows.values().stream().anyMatch(r -> r.date().equals(day.date()))) {
        throw new DateExistsException(null);
      }
      rows.put(day.id(), day);
    }

    @Override
    public boolean update(TenantId tenantId, HolidayDay day, int expectedVersion, UUID actorId) {
      if (consumeFailure()) {
        return false;
      }
      if (rows.values().stream()
          .anyMatch(r -> r.date().equals(day.date()) && !r.id().equals(day.id()))) {
        throw new DateExistsException(null);
      }
      HolidayDay cur = rows.get(day.id());
      if (cur == null || cur.rowVersion() != expectedVersion) {
        return false;
      }
      rows.put(day.id(), day);
      return true;
    }

    @Override
    public boolean delete(TenantId tenantId, UUID id, int expectedVersion) {
      if (consumeFailure()) {
        return false;
      }
      HolidayDay cur = rows.get(id);
      return cur != null && cur.rowVersion() == expectedVersion && rows.remove(id) != null;
    }

    @Override
    public Optional<HolidayDay> findById(TenantId tenantId, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<HolidayDay> all(TenantId tenantId) {
      loads.incrementAndGet();
      return rows.values().stream().sorted(Comparator.comparing(HolidayDay::date)).toList();
    }

    private boolean consumeFailure() {
      boolean f = failNextWrite;
      failNextWrite = false;
      return f;
    }
  }
}
