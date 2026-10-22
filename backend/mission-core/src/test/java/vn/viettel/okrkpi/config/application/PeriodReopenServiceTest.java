package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodReopenExpiredEvent;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.config.application.port.PeriodReopenRepository;
import vn.viettel.okrkpi.config.domain.DefaultPeriodCalendar;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodChangeCause;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodReopen;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.config.domain.ReopenScope;
import vn.viettel.okrkpi.config.domain.ReopenScopeTest;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-06: mở lại theo phạm vi, kiểm phạm vi khi ghi, hết hạn khoá lại, nhận trùng EVT-22. */
class PeriodReopenServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID ACTOR = UUID.randomUUID();
  // Thứ Hai 04/01/2027 9:00 giờ Hà Nội.
  private static final Instant OPENED = Instant.parse("2027-01-04T02:00:00Z");

  private final AtomicReference<Instant> now = new AtomicReference<>(OPENED);
  private final Clock clock = now::get;
  private final InMemoryPeriodRepository periods = new InMemoryPeriodRepository();
  private final Reopens reopens = new Reopens();
  private final List<PeriodTransition> outbox = new ArrayList<>();
  private final List<Object> published = new ArrayList<>();
  private final PeriodConfigService config =
      new PeriodConfigService(
          periods,
          (p, t, o, r) -> outbox.add(t),
          published::add,
          null,
          clock,
          new PeriodStateCache(Duration.ofSeconds(30)),
          TransactionOperations.withoutTransaction());
  private final HolidayCalendarService calendar =
      new HolidayCalendarService(
          new HolidayCalendarServiceTest.InMemory(),
          e -> {},
          TransactionOperations.withoutTransaction(),
          Duration.ofMinutes(1));
  private final PeriodReopenService service =
      new PeriodReopenService(reopens, periods, config, calendar, null, published::add, clock, 10);

  private final PeriodId period = PeriodId.of(UUID.randomUUID());
  private final UUID request = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();

  PeriodReopenServiceTest() {
    config.setReopens(reopens);
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 12, 31);
    Period p =
        Period.builder()
            .id(period)
            .tenantId(TENANT)
            .code("2026-Q4")
            .name("Quý IV/2026")
            .kind(PeriodKind.QUY)
            .flow(PeriodFlow.CA_HAI)
            .startDate(start)
            .endDate(end)
            .schedule(DefaultPeriodCalendar.scheduleOf(start, end))
            .created(ACTOR, OPENED)
            .build();
    p.moveTo(PeriodState.DANG_KHOA, UUID.randomUUID(), UUID.randomUUID(), ACTOR, OPENED);
    p.moveTo(PeriodState.DA_KHOA, UUID.randomUUID(), UUID.randomUUID(), ACTOR, OPENED);
    periods.put(p);
  }

  private boolean reopen(ReopenScope scope) {
    return service.onReopened(TENANT, period, request, scope, OPENED, null, ACTOR);
  }

  @Test
  void reopenInScopeWriteOutOfScopeRejectedThenExpiryRelocks() {
    assertThat(reopen(new ReopenScope(ReopenScope.Kind.DON_VI, Set.of(unit)))).isTrue();
    Period p = periods.findById(TENANT, period).orElseThrow();
    assertThat(p.state()).isEqualTo(PeriodState.MO_LAI_HAU_KIEM);
    assertThat(p.reopenCount()).isEqualTo(1);
    assertThat(outbox.getLast().cause()).isEqualTo(PeriodChangeCause.MO_LAI_HAU_KIEM);
    assertThat(outbox.getLast().causeRef()).isEqualTo(request);
    // Hạn mặc định: hết ngày làm việc thứ 10 sau 04/01/2027 là 18/01/2027 (giờ Hà Nội).
    assertThat(reopens.rows.get(request).openUntil())
        .isEqualTo(Instant.parse("2027-01-18T17:00:00Z"));

    // Trong phạm vi: ghi được; ngoài phạm vi hoặc không rõ đối tượng: 409 MSG-13-04.
    config.assertWritable(TENANT, period, PeriodWriteTarget.unit(unit));
    assertThatThrownBy(
            () -> config.assertWritable(TENANT, period, PeriodWriteTarget.unit(UUID.randomUUID())))
        .isInstanceOf(PeriodLockedException.class)
        .satisfies(e -> assertThat(((PeriodLockedException) e).detail()).contains("ngoài phạm vi"));
    assertThatThrownBy(() -> config.assertWritable(TENANT, period))
        .isInstanceOf(PeriodLockedException.class);

    // Nhận trùng EVT-22: không mở lại lần nữa.
    assertThat(reopen(new ReopenScope(ReopenScope.Kind.DON_VI, Set.of(unit)))).isFalse();
    assertThat(periods.findById(TENANT, period).orElseThrow().reopenCount()).isEqualTo(1);

    // Chưa tới hạn: timer không làm gì.
    assertThat(service.expireDue(TENANT, ACTOR)).isZero();
    now.set(Instant.parse("2027-01-18T17:00:00Z"));
    assertThat(service.due(TENANT)).hasSize(1);
    assertThat(service.expireDue(TENANT, ACTOR)).isEqualTo(1);
    assertThat(service.expireDue(TENANT, ACTOR)).isZero();
    assertThat(published).hasAtLeastOneElementOfType(PeriodReopenExpiredEvent.class);
    assertThat(periods.findById(TENANT, period).orElseThrow().state())
        .isEqualTo(PeriodState.DA_KHOA);
    assertThat(outbox.getLast().cause()).isEqualTo(PeriodChangeCause.KHOA_LAI);
    assertThatThrownBy(() -> config.assertWritable(TENANT, period, PeriodWriteTarget.unit(unit)))
        .isInstanceOf(PeriodLockedException.class)
        .satisfies(e -> assertThat(((PeriodLockedException) e).detail()).contains("đang khoá"));
    assertThat(service.completeRelock(TENANT, request, ACTOR)).isFalse();
  }

  @Test
  void wholePeriodScopeFinishEarlyAndIgnoredEvents() {
    // Kỳ chưa khoá: bỏ qua sự kiện.
    PeriodId open = PeriodId.of(UUID.randomUUID());
    assertThatThrownBy(
            () ->
                service.onReopened(
                    TENANT, open, UUID.randomUUID(), ReopenScopeTest.WHOLE, OPENED, null, ACTOR))
        .isInstanceOf(java.util.NoSuchElementException.class);

    Instant until = Instant.parse("2027-01-10T00:00:00Z");
    assertThat(
            service.onReopened(TENANT, period, request, ReopenScopeTest.WHOLE, null, until, ACTOR))
        .isTrue();
    assertThat(reopens.rows.get(request).openUntil()).isEqualTo(until);
    config.assertWritable(TENANT, period);
    // Sự kiện khác đến khi kỳ đang mở lại: bỏ qua.
    assertThat(
            service.onReopened(
                TENANT, period, UUID.randomUUID(), ReopenScopeTest.WHOLE, OPENED, null, ACTOR))
        .isFalse();
    assertThat(service.finish(TENANT, request, ACTOR)).isTrue();
    assertThat(service.finish(TENANT, request, ACTOR)).isFalse();
    assertThat(periods.findById(TENANT, period).orElseThrow().state())
        .isEqualTo(PeriodState.DA_KHOA);
    assertThat(published).noneMatch(PeriodReopenExpiredEvent.class::isInstance);
  }

  @Test
  void sagaIsStartedInsteadOfLocalRelockWhenConfigured() {
    List<UUID> started = new ArrayList<>();
    PeriodReopenService withSaga =
        new PeriodReopenService(
            reopens,
            periods,
            config,
            calendar,
            (r, by) -> {
              started.add(r.id());
              return UUID.randomUUID();
            },
            published::add,
            clock,
            1);
    assertThat(
            withSaga.onReopened(
                TENANT, period, request, ReopenScopeTest.WHOLE, OPENED, null, ACTOR))
        .isTrue();
    now.set(Instant.parse("2027-01-06T00:00:00Z"));
    assertThat(withSaga.expireDue(TENANT, ACTOR)).isEqualTo(1);
    assertThat(started).containsExactly(request);
    assertThat(periods.findById(TENANT, period).orElseThrow().state())
        .isEqualTo(PeriodState.MO_LAI_HAU_KIEM);
    assertThat(withSaga.completeRelock(TENANT, request, ACTOR)).isTrue();
    assertThat(periods.findById(TENANT, period).orElseThrow().state())
        .isEqualTo(PeriodState.DA_KHOA);
    assertThatThrownBy(
            () ->
                new PeriodReopenService(
                    reopens, periods, config, calendar, null, published::add, clock, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Kho bản chiếu mở lại trong bộ nhớ. */
  static final class Reopens implements PeriodReopenRepository {
    final Map<UUID, PeriodReopen> rows = new HashMap<>();

    @Override
    public boolean insertIfAbsent(PeriodReopen r, UUID actorId) {
      boolean openExists =
          rows.values().stream()
              .anyMatch(o -> o.periodId().equals(r.periodId()) && o.relockedAt() == null);
      if (rows.containsKey(r.id()) || openExists) {
        return false;
      }
      rows.put(r.id(), r);
      return true;
    }

    @Override
    public Optional<PeriodReopen> findById(TenantId tenantId, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<PeriodReopen> findOpen(TenantId tenantId, PeriodId periodId) {
      return rows.values().stream()
          .filter(o -> o.periodId().equals(periodId) && o.relockedAt() == null)
          .findFirst();
    }

    @Override
    public List<PeriodReopen> dueForRelock(TenantId tenantId, Instant now, int limit) {
      return rows.values().stream()
          .filter(o -> o.relockStartedAt() == null && o.due(now))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean markRelockStarted(TenantId tenantId, UUID id, Instant at, UUID actorId) {
      PeriodReopen o = rows.get(id);
      if (o.relockStartedAt() != null || o.relockedAt() != null) {
        return false;
      }
      rows.put(id, copy(o, at, null));
      return true;
    }

    @Override
    public boolean markRelocked(TenantId tenantId, UUID id, Instant at, UUID actorId) {
      PeriodReopen o = rows.get(id);
      if (o.relockedAt() != null) {
        return false;
      }
      rows.put(id, copy(o, o.relockStartedAt() == null ? at : o.relockStartedAt(), at));
      return true;
    }

    private static PeriodReopen copy(PeriodReopen o, Instant started, Instant relocked) {
      return new PeriodReopen(
          o.id(),
          o.tenantId(),
          o.periodId(),
          o.scope(),
          o.openedAt(),
          o.openUntil(),
          started,
          relocked);
    }
  }
}
