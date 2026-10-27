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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.LockOrderRepository;
import vn.viettel.okrkpi.config.application.port.PeriodLockSaga;
import vn.viettel.okrkpi.config.domain.DefaultPeriodCalendar;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-05: lập, duyệt, khởi chạy lệnh khoá kỳ với tách vai trò và MFA bước lên. */
class PeriodLockServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-12-28T02:00:00Z");
  private static final UUID ADMIN = UUID.randomUUID();
  private static final UUID LEADER = UUID.randomUUID();
  private static final UUID ADMIN2 = UUID.randomUUID();

  private final InMemoryPeriodRepository periods = new InMemoryPeriodRepository();
  private final Orders orders = new Orders();
  private final List<String> notices = new ArrayList<>();
  private final List<String> sagaCalls = new ArrayList<>();
  private boolean sagaExists;
  private final Clock clock = () -> NOW;
  private final PeriodLockService service =
      new PeriodLockService(
          orders,
          periods,
          (t, p, o, by, key) -> {
            if (sagaExists) {
              throw new PeriodLockSaga.SagaExistsException(null);
            }
            sagaCalls.add(key);
            return UUID.nameUUIDFromBytes(key.getBytes());
          },
          new LockOrderNotices() {
            @Override
            public void requested(PeriodLockOrder order, String name, List<UUID> to) {
              notices.add("requested:" + name + ":" + to);
            }

            @Override
            public void approved(PeriodLockOrder order, String name, List<UUID> to) {
              notices.add("approved:" + to);
            }
          },
          (tenant, role, at) -> "leader".equals(role) ? List.of(LEADER) : List.of(ADMIN, ADMIN2),
          new HolidayCalendarService(
              new HolidayCalendarServiceTest.InMemory(),
              e -> {},
              TransactionOperations.withoutTransaction(),
              Duration.ofMinutes(1)),
          clock);

  private final PeriodId period = PeriodId.of(UUID.randomUUID());

  PeriodLockServiceTest() {
    periods.put(period(period, PeriodState.MO));
  }

  private static Period period(PeriodId id, PeriodState state) {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 12, 31);
    Period p =
        Period.builder()
            .id(id)
            .tenantId(TENANT)
            .code("2026-Q4")
            .name("Quý IV/2026")
            .kind(PeriodKind.QUY)
            .flow(PeriodFlow.CA_HAI)
            .startDate(start)
            .endDate(end)
            .schedule(DefaultPeriodCalendar.scheduleOf(start, end))
            .created(ADMIN, NOW)
            .build();
    if (state == PeriodState.DANG_KHOA) {
      p.moveTo(PeriodState.DANG_KHOA, UUID.randomUUID(), UUID.randomUUID(), ADMIN, NOW);
    }
    return p;
  }

  private static Actor mfa(UUID id) {
    return new Actor(id, "aal2", NOW.getEpochSecond() - 60);
  }

  private PeriodLockOrder created() {
    return service.create(
        TENANT, mfa(ADMIN), period, "Thông báo 21-TB/ĐUK", Instant.parse("2026-12-29T00:00:00Z"));
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  @Test
  void fullFlowCreateApproveStartAndReplay() {
    PeriodLockOrder order = created();
    assertThat(order.status()).isEqualTo(LockOrderStatus.CHO_DUYET);
    assertThat(order.idempotencyKey()).isEqualTo("CK-2026Q4");
    assertThat(notices).containsExactly("requested:Quý IV/2026:[" + LEADER + "]");

    PeriodLockOrder approved = service.approve(TENANT, mfa(LEADER), period, order.id(), 0);
    assertThat(approved.status()).isEqualTo(LockOrderStatus.DA_DUYET);
    assertThat(notices.get(1)).contains(ADMIN.toString()).contains(ADMIN2.toString());

    PeriodLockService.Started s =
        service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-2026Q4", 1L);
    assertThat(s.replay()).isFalse();
    assertThat(s.order().status()).isEqualTo(LockOrderStatus.DANG_CHAY);
    PeriodLockService.Started again =
        service.start(TENANT, mfa(ADMIN2), period, order.id(), "CK-2026Q4", null);
    assertThat(again.replay()).isTrue();
    assertThat(again.jobId()).isEqualTo(s.jobId());
    assertThat(sagaCalls).containsExactly("CK-2026Q4");
    assertThatThrownBy(() -> service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-KHAC", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-RUNNING"));

    PeriodLockService.Progress p = service.progress(TENANT, period).orElseThrow();
    assertThat(p.steps()).hasSize(6);
    assertThat(p.steps().get(0).name()).isEqualTo("Kiểm tra tiền điều kiện");
    assertThat(p.cancellable()).isTrue();
    orders.steps.put(
        order.id(), List.of(new LockStep(4, "Ký số lô", "DAT", NOW, NOW, Map.of(), null, null)));
    assertThat(service.progress(TENANT, period).orElseThrow().cancellable()).isFalse();
    assertThat(service.find(TENANT, period, order.id())).isPresent();
  }

  @Test
  void createRejectsClosedPeriodBadInputAndDuplicates() {
    PeriodId locked = PeriodId.of(UUID.randomUUID());
    periods.put(period(locked, PeriodState.DANG_KHOA));
    assertThatThrownBy(() -> service.create(TENANT, mfa(ADMIN), locked, "x", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-422-PRECONDITION"));
    assertThatThrownBy(
            () -> service.create(TENANT, mfa(ADMIN), PeriodId.of(UUID.randomUUID()), "x", null))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.create(TENANT, mfa(ADMIN), period, " ", null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT, mfa(ADMIN), period, "x", Instant.parse("2026-12-28T05:00:00Z")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    PeriodLockOrder order = created();
    assertThatThrownBy(this::created)
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-ORDER-EXISTS"));
    orders.raceOnInsert = true;
    orders.rows.clear();
    assertThatThrownBy(this::created)
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-ORDER-EXISTS"));
    orders.raceOnInsert = false;
    // Lệnh trước đã huỷ thì lệnh sau có hậu tố khoá.
    orders.keys.add(order.idempotencyKey());
    assertThat(created().idempotencyKey()).isEqualTo("CK-2026Q4-2");
  }

  @Test
  void approveEnforcesPendingFourEyesStepUpAndVersion() {
    PeriodLockOrder order = created();
    assertThatThrownBy(() -> service.approve(TENANT, mfa(ADMIN), period, order.id(), 0))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-403-SAME-PERSON"));
    assertThatThrownBy(
            () -> service.approve(TENANT, new Actor(LEADER, "aal1", null), period, order.id(), 0))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT,
                    new Actor(LEADER, "aal2", NOW.getEpochSecond() - 16 * 60),
                    period,
                    order.id(),
                    0))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> service.approve(TENANT, mfa(LEADER), period, order.id(), 3))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.approve(TENANT, mfa(LEADER), PeriodId.of(UUID.randomUUID()), order.id(), 0))
        .isInstanceOf(NoSuchElementException.class);
    orders.failUpdate = true;
    assertThatThrownBy(() -> service.approve(TENANT, mfa(LEADER), period, order.id(), 0))
        .isInstanceOf(VersionConflictException.class);
    orders.failUpdate = false;
    // Kho trong bộ nhớ giữ cùng đối tượng nên lần ghi hỏng vẫn đổi trạng thái; lập lệnh mới.
    orders.rows.clear();
    PeriodLockOrder fresh = created();
    service.approve(TENANT, mfa(LEADER), period, fresh.id(), 0);
    assertThatThrownBy(() -> service.approve(TENANT, mfa(LEADER), period, fresh.id(), 1))
        .satisfies(e -> assertThat(code(e)).isEqualTo("CONFIG-409-NOT-PENDING"));
  }

  @Test
  void startEnforcesApprovalKeyFourEyesStepUpIfMatchAndSagaConflict() {
    PeriodLockOrder order = created();
    assertThatThrownBy(() -> service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-2026Q4", 0L))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-422-PRECONDITION"));
    service.approve(TENANT, mfa(LEADER), period, order.id(), 0);
    assertThatThrownBy(() -> service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-X", 1L))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.start(TENANT, mfa(LEADER), period, order.id(), "CK-2026Q4", 1L))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-403-SAME-PERSON"));
    assertThatThrownBy(
            () ->
                service.start(
                    TENANT, new Actor(ADMIN, null, null), period, order.id(), "CK-2026Q4", 1L))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(
            () -> service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-2026Q4", null))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-2026Q4", 0L))
        .isInstanceOf(VersionConflictException.class);
    sagaExists = true;
    assertThatThrownBy(() -> service.start(TENANT, mfa(ADMIN), period, order.id(), "CK-2026Q4", 1L))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-RUNNING"));
    assertThat(service.progress(TENANT, period)).isEmpty();
    assertThatThrownBy(
            () -> service.start(TENANT, mfa(ADMIN), period, UUID.randomUUID(), "CK-2026Q4", 1L))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void domainStatusMachine() {
    assertThat(LockOrderStatus.NHAP.canMoveTo(LockOrderStatus.CHO_DUYET)).isTrue();
    assertThat(LockOrderStatus.CHO_DUYET.canMoveTo(LockOrderStatus.DANG_CHAY)).isFalse();
    assertThat(LockOrderStatus.DANG_CHAY.canMoveTo(LockOrderStatus.HOAN_TAT)).isTrue();
    assertThat(LockOrderStatus.HOAN_TAT.next()).isEmpty();
    assertThat(LockOrderStatus.DA_HUY.active()).isFalse();
    assertThat(LockOrderStatus.DA_DUYET.canMoveTo(LockOrderStatus.DA_HUY)).isTrue();
    PeriodLockOrder o =
        PeriodLockOrder.request(UUID.randomUUID(), TENANT, period, "x", null, ADMIN, NOW, "CK-1");
    assertThatThrownBy(() -> o.approve(ADMIN, NOW))
        .isInstanceOf(PeriodLockOrder.SamePersonException.class);
    assertThatThrownBy(() -> o.start(ADMIN2, UUID.randomUUID(), NOW))
        .isInstanceOf(PeriodLockOrder.IllegalStatusException.class)
        .satisfies(
            e ->
                assertThat(((PeriodLockOrder.IllegalStatusException) e).from())
                    .isEqualTo(LockOrderStatus.CHO_DUYET));
    o.approve(LEADER, NOW);
    assertThatThrownBy(() -> o.start(LEADER, UUID.randomUUID(), NOW))
        .isInstanceOf(PeriodLockOrder.SamePersonException.class);
    o.start(ADMIN, UUID.randomUUID(), NOW);
    assertThat(o.rowVersion()).isEqualTo(2);
    assertThat(o.startedBy()).isEqualTo(ADMIN);
    assertThat(LockStep.pending(5).done()).isFalse();
  }

  /** Kho lệnh trong bộ nhớ. */
  static final class Orders implements LockOrderRepository {
    final Map<UUID, PeriodLockOrder> rows = new HashMap<>();
    final Map<UUID, List<LockStep>> steps = new HashMap<>();
    final List<String> keys = new ArrayList<>();
    boolean raceOnInsert;
    boolean failUpdate;

    @Override
    public void insert(PeriodLockOrder order, UUID actorId) {
      if (raceOnInsert) {
        throw new ActiveOrderExistsException(null);
      }
      rows.put(order.id(), order);
    }

    @Override
    public boolean update(PeriodLockOrder order, int expectedVersion, UUID actorId) {
      if (failUpdate) {
        return false;
      }
      rows.put(order.id(), order);
      return true;
    }

    @Override
    public Optional<PeriodLockOrder> findById(TenantId tenantId, UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<PeriodLockOrder> lockById(TenantId tenantId, UUID id) {
      return findById(tenantId, id);
    }

    @Override
    public Optional<PeriodLockOrder> active(TenantId tenantId, PeriodId periodId) {
      return rows.values().stream()
          .filter(o -> o.periodId().equals(periodId) && o.status().active())
          .findFirst();
    }

    @Override
    public Optional<PeriodLockOrder> latest(TenantId tenantId, PeriodId periodId) {
      return active(tenantId, periodId);
    }

    @Override
    public int countKeys(TenantId tenantId, String keyPrefix) {
      return (int) keys.stream().filter(k -> k.startsWith(keyPrefix)).count();
    }

    @Override
    public List<LockStep> steps(TenantId tenantId, UUID lockOrderId) {
      return steps.getOrDefault(lockOrderId, List.of());
    }

    @Override
    public Optional<PeriodLockOrder> findBySaga(TenantId tenantId, UUID sagaId) {
      return rows.values().stream().filter(o -> sagaId.equals(o.sagaId())).findFirst();
    }

    @Override
    public void upsertStep(TenantId tenantId, UUID lockOrderId, LockStep step) {
      List<LockStep> list = new ArrayList<>(steps.getOrDefault(lockOrderId, List.of()));
      list.removeIf(x -> x.stepNo() == step.stepNo());
      list.add(step);
      steps.put(lockOrderId, list);
    }
  }
}
