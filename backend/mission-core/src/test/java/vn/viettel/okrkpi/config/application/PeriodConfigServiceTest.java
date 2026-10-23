package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.config.api.PeriodCreatedEvent;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodOpenedEvent;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.application.port.PeriodConflictException;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.IllegalPeriodTransitionException;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodChangeCause;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodMilestones;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ kỳ đánh giá trên kho trong bộ nhớ (CORE-08-02). */
class PeriodConfigServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID ACTOR = UUID.randomUUID();
  // 09:00 ngày 15/12/2026 giờ Việt Nam.
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"));

  private InMemoryPeriodRepository repo;
  private final List<Object> published = new ArrayList<>();
  private final List<PeriodTransition> outbox = new ArrayList<>();
  private final List<String> reasons = new ArrayList<>();
  private PeriodConfigService service;

  @BeforeEach
  void setUp() {
    repo = new InMemoryPeriodRepository();
    published.clear();
    outbox.clear();
    service = service(null);
  }

  private PeriodConfigService service(PeriodCalendarPort calendar) {
    return new PeriodConfigService(
        repo,
        (p, t, order, reason) -> {
          outbox.add(t);
          reasons.add(reason);
        },
        published::add,
        calendar,
        CLOCK);
  }

  private static NewPeriod quarter(String code, LocalDate start, LocalDate end) {
    return new NewPeriod(
        code, "Kỳ " + code, PeriodKind.QUY, PeriodFlow.CA_HAI, null, start, end, null, null, null);
  }

  private static NewPeriod q1() {
    return quarter("2027-Q1", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31));
  }

  private static NewPeriod q4() {
    return quarter("2026-Q4", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
  }

  @Test
  void createsFuturePeriodWithDefaultScheduleAndPublishesCreatedOnly() {
    PeriodView v = service.create(TENANT, ACTOR, q1());
    Period p = v.period();
    assertThat(v.notStarted()).isTrue();
    assertThat(v.warnings()).isEmpty();
    assertThat(p.state()).isEqualTo(PeriodState.MO);
    assertThat(p.bucket()).isEqualTo(20271);
    assertThat(p.schedule().planDeadline()).isEqualTo(LocalDate.of(2027, 1, 8));
    assertThat(p.schedule().milestones().plannedLockDate()).isEqualTo(LocalDate.of(2027, 4, 5));
    assertThat(published).singleElement().isInstanceOf(PeriodCreatedEvent.class);
    PeriodCreatedEvent e = (PeriodCreatedEvent) published.get(0);
    assertThat(e.periodBucket()).isEqualTo(20271);
    assertThat(e.flow()).isEqualTo("CA_HAI");
  }

  @Test
  void preparesPartitionsBeforeInsertAndNotForRejectedPeriods() {
    List<String> calls = new ArrayList<>();
    service.setPartitions(
        bucket ->
            calls.add(
                bucket
                    + ":"
                    + repo.covering(TENANT, PeriodKind.QUY, LocalDate.of(2027, 1, 15)).size()));
    service.create(TENANT, ACTOR, q1());
    // Phân vùng được chuẩn bị trước khi kỳ được ghi.
    assertThat(calls).containsExactly("20271:0");
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT,
                    ACTOR,
                    quarter("2027-Q1b", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 5, 31))))
        .hasMessage("PERIOD-409-OVERLAP");
    assertThat(calls).hasSize(1);
  }

  @Test
  void createsStartedPeriodAndAnnouncesOpeningOnce() {
    PeriodView v = service.create(TENANT, ACTOR, q4());
    assertThat(v.notStarted()).isFalse();
    assertThat(published).hasSize(2).last().isInstanceOf(PeriodOpenedEvent.class);
    assertThat(service.announceOpenings(TENANT)).isZero();
    assertThat(published).hasSize(2);
  }

  @Test
  void openingTaskAnnouncesPeriodsStartingTodayOrYesterday() {
    PeriodView v = service.create(TENANT, ACTOR, q1());
    PeriodConfigService onStart =
        new PeriodConfigService(
            repo,
            (p, t, o, r) -> {},
            published::add,
            null,
            Clock.fixed(Instant.parse("2027-01-02T02:00:00Z")));
    assertThat(onStart.announceOpenings(TENANT)).isEqualTo(1);
    assertThat(onStart.announceOpenings(TENANT)).isZero();
    assertThat(published.get(1)).isInstanceOf(PeriodOpenedEvent.class);
    assertThat(((PeriodOpenedEvent) published.get(1)).periodId()).isEqualTo(v.period().id());
    onStart.openPeriod(PeriodConfigService.snapshot(v.period()));
    assertThat(published).hasSize(3);
  }

  @Test
  void rejectsOverlapInvalidDatesAndMissingParent() {
    service.create(TENANT, ACTOR, q1());
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT,
                    ACTOR,
                    quarter("2027-Q1b", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 5, 31))))
        .isInstanceOf(BusinessException.class)
        .hasMessage("PERIOD-409-OVERLAP");
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT,
                    ACTOR,
                    quarter("x", LocalDate.of(2027, 6, 30), LocalDate.of(2027, 4, 1))))
        .hasMessage("COMMON-422-VALIDATION");
    NewPeriod orphan =
        new NewPeriod(
            "2027-Q2",
            "Quý II/2027",
            PeriodKind.QUY,
            PeriodFlow.CA_HAI,
            PeriodId.of(UUID.randomUUID()),
            LocalDate.of(2027, 4, 1),
            LocalDate.of(2027, 6, 30),
            null,
            null,
            null);
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, orphan))
        .hasMessage("COMMON-422-VALIDATION");
    NewPeriod noName =
        new NewPeriod(
            "2027-Q3",
            null,
            PeriodKind.QUY,
            PeriodFlow.CA_HAI,
            null,
            LocalDate.of(2027, 7, 1),
            LocalDate.of(2027, 9, 30),
            null,
            null,
            null);
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, noName))
        .hasMessage("COMMON-422-VALIDATION");
    // Cùng khoảng nhưng khác luồng thì không chồng lấn.
    NewPeriod party =
        new NewPeriod(
            "2027-Q1",
            "Quý I/2027",
            PeriodKind.QUY,
            PeriodFlow.DANG,
            null,
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 3, 31),
            null,
            null,
            null);
    assertThat(service.create(TENANT, ACTOR, party).period().flow()).isEqualTo(PeriodFlow.DANG);
  }

  @Test
  void mapsDatabaseConflictsRaisedOnInsert() {
    PeriodRepository racing =
        new InMemoryPeriodRepository() {
          @Override
          public void insert(Period p) {
            throw new PeriodConflictException(
                p.code().equals("dup")
                    ? PeriodConflictException.Kind.DUPLICATE_CODE
                    : PeriodConflictException.Kind.OVERLAP,
                null);
          }
        };
    PeriodConfigService s =
        new PeriodConfigService(racing, (p, t, o, r) -> {}, e -> {}, null, CLOCK);
    assertThatThrownBy(() -> s.create(TENANT, ACTOR, q1())).hasMessage("PERIOD-409-OVERLAP");
    assertThatThrownBy(
            () ->
                s.create(
                    TENANT,
                    ACTOR,
                    quarter("dup", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))))
        .hasMessage("COMMON-422-VALIDATION");
  }

  @Test
  void usesPolicyPackCalendarAndRequestOverridesAndWarnsOnLateLock() {
    PeriodCalendarPort pack =
        (tenant, kind, flow, start, end) ->
            Optional.of(
                new PeriodCalendarPort.PeriodCalendar(
                    LocalDate.of(2027, 1, 6), null, null, null, LocalDate.of(2027, 4, 12)));
    PeriodView v = service(pack).create(TENANT, ACTOR, q1());
    assertThat(v.period().schedule().planDeadline()).isEqualTo(LocalDate.of(2027, 1, 6));
    assertThat(v.period().schedule().milestones().acceptanceBacklogDeadline())
        .isEqualTo(LocalDate.of(2027, 4, 2));
    assertThat(v.warnings()).containsExactly(PeriodConfigService.WARNING_LATE_LOCK);
    assertThat(v.warnings().get(0)).doesNotContain("BR-", "DA_KHOA", "_");

    PeriodCalendarPort none = (tenant, kind, flow, start, end) -> Optional.empty();
    NewPeriod withOverride =
        new NewPeriod(
            "2027-Q2",
            "Quý II/2027",
            PeriodKind.QUY,
            PeriodFlow.CA_HAI,
            null,
            LocalDate.of(2027, 4, 1),
            LocalDate.of(2027, 6, 30),
            LocalDate.of(2027, 4, 7),
            LocalDate.of(2027, 7, 8),
            new PeriodMilestones(null, LocalDate.of(2027, 6, 25), null, null));
    Period p = service(none).create(TENANT, ACTOR, withOverride).period();
    assertThat(p.schedule().planDeadline()).isEqualTo(LocalDate.of(2027, 4, 7));
    assertThat(p.schedule().submissionDeadline()).isEqualTo(LocalDate.of(2027, 7, 8));
    assertThat(p.schedule().milestones().evidenceDeadline()).isEqualTo(LocalDate.of(2027, 6, 25));
  }

  @Test
  void transitionsWriteStatusLogAndOutboxAndGuardWrites() {
    PeriodId id = service.create(TENANT, ACTOR, q4()).period().id();
    UUID saga = UUID.randomUUID();
    service.transition(TENANT, id, PeriodState.DANG_KHOA, saga, ACTOR, null, null);
    service.assertOpen(TENANT, id);
    assertThatThrownBy(() -> service.assertWritable(TENANT, id))
        .isInstanceOf(PeriodLockedException.class);
    UUID order = UUID.randomUUID();
    service.transition(TENANT, id, PeriodState.MO, saga, ACTOR, order, "Hồ sơ lỗi");
    service.assertWritable(TENANT, id);
    service.transition(TENANT, id, PeriodState.DANG_KHOA, saga, ACTOR, null, null);
    Period locked = service.transition(TENANT, id, PeriodState.DA_KHOA, saga, ACTOR, null, null);
    assertThat(locked.lockedAt()).isEqualTo(CLOCK.instant());
    assertThatThrownBy(() -> service.assertOpen(TENANT, id))
        .isInstanceOf(PeriodLockedException.class);
    assertThat(repo.statusLog)
        .extracting(PeriodTransition::cause)
        .containsExactly(
            PeriodChangeCause.SAGA_CHOT_KY,
            PeriodChangeCause.BU_TRU_SAGA,
            PeriodChangeCause.SAGA_CHOT_KY,
            PeriodChangeCause.SAGA_CHOT_KY);
    assertThat(outbox).hasSize(4);
    assertThat(reasons).contains("Hồ sơ lỗi");
    assertThat(service.get(TENANT, id).orElseThrow().period().rowVersion()).isEqualTo(4);
    assertThatThrownBy(
            () -> service.transition(TENANT, id, PeriodState.MO, saga, ACTOR, null, null))
        .isInstanceOf(IllegalPeriodTransitionException.class);
  }

  @Test
  void transitionFailsOnConcurrentUpdate() {
    PeriodId id = service.create(TENANT, ACTOR, q4()).period().id();
    PeriodRepository stale =
        new InMemoryPeriodRepository() {
          @Override
          public Optional<Period> findById(TenantId t, PeriodId p) {
            return repo.findById(t, p);
          }

          @Override
          public boolean updateState(Period p, int expectedVersion, UUID actorId) {
            return false;
          }
        };
    PeriodConfigService s =
        new PeriodConfigService(stale, (p, t, o, r) -> {}, e -> {}, null, CLOCK);
    assertThatThrownBy(
            () ->
                s.transition(
                    TENANT, id, PeriodState.DANG_KHOA, UUID.randomUUID(), ACTOR, null, null))
        .isInstanceOf(ConcurrentModificationException.class);
  }

  @Test
  void currentPrefersQuarterThenNewestAndSupportsCadence() {
    PeriodId quarterId = service.create(TENANT, ACTOR, q4()).period().id();
    NewPeriod year =
        new NewPeriod(
            "2026",
            "Năm 2026",
            PeriodKind.NAM,
            PeriodFlow.CA_HAI,
            null,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            null,
            null,
            null);
    PeriodId yearId = service.create(TENANT, ACTOR, year).period().id();
    assertThat(service.findCurrent(TENANT).map(PeriodSnapshot::id)).contains(quarterId);
    assertThat(service.current(TENANT, "NAM").id()).isEqualTo(yearId);
    assertThat(service.current(TENANT, "QUY").status()).isEqualTo(PeriodStatus.MO);
    assertThat(service.findCurrent(TENANT, "THANG")).isEmpty();
    assertThatThrownBy(() -> service.current(TENANT, "THANG"))
        .isInstanceOf(java.util.NoSuchElementException.class);
    assertThat(service.findById(TENANT, quarterId)).isPresent();
    assertThat(service.findById(TenantId.of(UUID.randomUUID()), quarterId)).isEmpty();
  }

  @Test
  void listsNewestFirstWithKeysetAndFilters() {
    service.create(TENANT, ACTOR, q4());
    service.create(TENANT, ACTOR, q1());
    service.create(
        TENANT, ACTOR, quarter("2027-Q2", LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30)));
    List<PeriodView> first = service.list(TENANT, null, null, 2);
    assertThat(first).extracting(v -> v.period().code()).containsExactly("2027-Q2", "2027-Q1");
    Period last = first.get(1).period();
    List<PeriodView> next =
        service.list(
            TENANT, null, new PeriodRepository.Keyset(last.startDate(), last.id().value()), 2);
    assertThat(next).extracting(v -> v.period().code()).containsExactly("2026-Q4");
    assertThat(service.list(TENANT, new PeriodRepository.Filter(2026, null, null, null), null, 10))
        .hasSize(1);
  }
}
