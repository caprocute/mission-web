package vn.viettel.okrkpi.config.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.LockOrderRepository;
import vn.viettel.okrkpi.config.application.port.PeriodLockSaga;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.application.port.RoleHolders;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh khoá kỳ của MOD-08 (CORE-08-05, QT-08 bước 1–2): lập, duyệt, khởi chạy saga {@code chot-ky}
 * và đọc tiến trình. Ngoài OPA ở controller ({@code SCR-0801.lap-lenh}, {@code duyet-lenh}, {@code
 * khoi-chay}), dịch vụ tự chặn tách vai trò (BR-08-01) và MFA bước lên trong 15 phút khi duyệt và
 * khởi chạy (BR-08-02), vì máy chủ là nơi kiểm soát cuối cùng.
 */
public class PeriodLockService {

  static final String TX = "configTransactionManager";

  /** Cửa sổ MFA bước lên của BR-08-02. */
  public static final Duration STEP_UP_WINDOW = Duration.ofMinutes(15);

  static final String LEADER = "leader";
  static final String TENANT_ADMIN = "tenant_admin";
  private static final int BASIS_MAX = 200;
  private static final int KEY_MAX = 64;

  /** Kết quả khởi chạy: lệnh và mã job (mã saga). */
  public record Started(PeriodLockOrder order, String jobId, boolean replay) {}

  /** Tiến trình cho {@code getPeriodLockProgress}. */
  public record Progress(PeriodLockOrder order, List<LockStep> steps) {

    /** Còn huỷ được: trước khi bước 4 (ký số lô) hoàn tất (BR-08-09). */
    public boolean cancellable() {
      return order.status() == LockOrderStatus.DANG_CHAY && !steps.get(4).done();
    }
  }

  private final LockOrderRepository orders;
  private final PeriodRepository periods;
  private final PeriodLockSaga saga;
  private final LockOrderNotices notices;
  private final RoleHolders roles;
  private final WorkingCalendarApi calendar;
  private final Clock clock;

  public PeriodLockService(
      LockOrderRepository orders,
      PeriodRepository periods,
      PeriodLockSaga saga,
      LockOrderNotices notices,
      RoleHolders roles,
      WorkingCalendarApi calendar,
      Clock clock) {
    this.orders = Objects.requireNonNull(orders, "orders");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.saga = Objects.requireNonNull(saga, "saga");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.roles = Objects.requireNonNull(roles, "roles");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * {@code createPeriodLockOrder}: kỳ phải {@code MO} hoặc {@code MO_LAI_HAU_KIEM}, chưa có lệnh
   * chưa huỷ; thời điểm dự kiến (nếu có) phải sau hôm nay ít nhất 1 ngày làm việc (QT-08 bước 1).
   *
   * @throws NoSuchElementException khi không có kỳ
   */
  @Transactional(TX)
  public PeriodLockOrder create(
      TenantId tenantId, Actor actor, PeriodId periodId, String basisDoc, Instant plannedLockAt) {
    Period period = periods.findById(tenantId, periodId).orElseThrow();
    if (period.state() != PeriodState.MO && period.state() != PeriodState.MO_LAI_HAU_KIEM) {
      throw new BusinessException("PERIOD-422-PRECONDITION", "Chỉ lập lệnh khoá cho kỳ đang mở.");
    }
    if (basisDoc == null || basisDoc.isBlank() || basisDoc.strip().length() > BASIS_MAX) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Cần nhập văn bản căn cứ, không quá 200 ký tự.");
    }
    if (plannedLockAt != null) {
      LocalDate earliest = calendar.addWorkingDays(tenantId, clock.businessDate(), 1);
      if (LocalDate.ofInstant(plannedLockAt, Clock.BUSINESS_ZONE).isBefore(earliest)) {
        throw new BusinessException(
            "COMMON-422-VALIDATION", "Thời điểm dự kiến phải sau hôm nay ít nhất 1 ngày làm việc.");
      }
    }
    if (orders.active(tenantId, periodId).isPresent()) {
      throw orderExists();
    }
    PeriodLockOrder order =
        PeriodLockOrder.request(
            Ids.newUuidV7(),
            tenantId,
            periodId,
            basisDoc.strip(),
            plannedLockAt,
            actor.userId(),
            clock.instant(),
            idempotencyKey(tenantId, period));
    try {
      orders.insert(order, actor.userId());
    } catch (LockOrderRepository.ActiveOrderExistsException e) {
      throw orderExists();
    }
    notices.requested(order, period.name(), roles.holders(tenantId, LEADER, clock.instant()));
    return order;
  }

  /**
   * {@code approvePeriodLockOrder}: lệnh {@code CHO_DUYET}, người duyệt khác người lập, MFA bước
   * lên còn hạn; phát EVT-21 MSG-08-01 cho người lập và quản trị tenant.
   */
  @Transactional(TX)
  public PeriodLockOrder approve(
      TenantId tenantId, Actor actor, PeriodId periodId, UUID orderId, long expectedVersion) {
    PeriodLockOrder order = orderOf(tenantId, periodId, orderId);
    if (order.status() != LockOrderStatus.CHO_DUYET) {
      throw new BusinessException(
          "CONFIG-409-NOT-PENDING", "Lệnh khoá kỳ không ở trạng thái chờ duyệt.");
    }
    if (actor.userId().equals(order.requestedBy())) {
      throw samePerson("Người phê duyệt phải khác người lập lệnh khoá kỳ.");
    }
    requireStepUp(actor, "approvePeriodLockOrder");
    requireVersion(order, expectedVersion);
    int before = order.rowVersion();
    order.approve(actor.userId(), clock.instant());
    save(order, before, actor);
    LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
    recipients.add(order.requestedBy());
    recipients.addAll(roles.holders(tenantId, TENANT_ADMIN, clock.instant()));
    recipients.remove(actor.userId());
    notices.approved(order, periodName(tenantId, periodId), new ArrayList<>(recipients));
    return order;
  }

  /**
   * {@code startPeriodLock}: khoá chống lặp phải đúng {@code CK-…} của lệnh; gọi lại cùng khoá khi
   * saga đã chạy trả lại job cũ; lệnh khác đang chạy trả {@code PERIOD-409-SAGA-RUNNING}
   * (BR-08-08); người khởi chạy khác người duyệt (BR-08-01), MFA bước lên còn hạn (BR-08-02).
   *
   * @param expectedVersion phiên bản trong {@code If-Match}, {@code null} khi gọi lại
   */
  @Transactional(TX)
  public Started start(
      TenantId tenantId,
      Actor actor,
      PeriodId periodId,
      UUID orderId,
      String idempotencyKey,
      Long expectedVersion) {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    PeriodLockOrder order =
        orders
            .lockById(tenantId, orderId)
            .filter(o -> o.periodId().equals(periodId))
            .orElseThrow(() -> new NoSuchElementException("lockOrder"));
    if (order.status() == LockOrderStatus.DANG_CHAY || order.status() == LockOrderStatus.HOAN_TAT) {
      if (idempotencyKey.equals(order.idempotencyKey()) && order.sagaId() != null) {
        return new Started(order, order.sagaId().toString(), true);
      }
      throw new BusinessException(
          "PERIOD-409-SAGA-RUNNING", "Kỳ đã có saga khoá kỳ đang chạy hoặc đã hoàn tất.");
    }
    if (order.status() != LockOrderStatus.DA_DUYET) {
      throw new BusinessException("PERIOD-422-PRECONDITION", "Lệnh khoá kỳ chưa được phê duyệt.");
    }
    if (!idempotencyKey.equals(order.idempotencyKey())) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Khoá chống lặp của lệnh khởi chạy phải là " + order.idempotencyKey() + ".");
    }
    if (actor.userId().equals(order.approvedBy())) {
      throw samePerson("Người khởi chạy phải khác người phê duyệt lệnh khoá kỳ.");
    }
    requireStepUp(actor, "startPeriodLock");
    if (expectedVersion == null) {
      throw new vn.viettel.okrkpi.shared.error.PreconditionRequiredException();
    }
    requireVersion(order, expectedVersion);
    order.checkStartable(actor.userId());
    UUID sagaId;
    try {
      sagaId = saga.start(tenantId, periodId, orderId, actor.userId(), order.idempotencyKey());
    } catch (PeriodLockSaga.SagaExistsException e) {
      throw new BusinessException(
          "PERIOD-409-SAGA-RUNNING", "Kỳ đã có saga khoá kỳ đang chạy hoặc đã hoàn tất.");
    }
    int before = order.rowVersion();
    order.start(actor.userId(), sagaId, clock.instant());
    save(order, before, actor);
    return new Started(order, sagaId.toString(), false);
  }

  /** {@code getPeriodLockProgress}: lệnh mới nhất của kỳ đã có saga, kèm sáu bước. */
  @Transactional(value = TX, readOnly = true)
  public Optional<Progress> progress(TenantId tenantId, PeriodId periodId) {
    return orders
        .latest(tenantId, periodId)
        .filter(o -> o.sagaId() != null)
        .map(o -> new Progress(o, sixSteps(orders.steps(tenantId, o.id()))));
  }

  /**
   * Tiến độ theo mã saga cho tác vụ {@code PERIOD_LOCK} ({@code /api/v1/jobs/{id}}): mã tác vụ trả
   * lúc khởi chạy là mã saga (QA-06 HT-23).
   */
  @Transactional(value = TX, readOnly = true)
  public Optional<Progress> progressOfSaga(TenantId tenantId, UUID sagaId) {
    return orders
        .findBySaga(tenantId, sagaId)
        .map(o -> new Progress(o, sixSteps(orders.steps(tenantId, o.id()))));
  }

  /**
   * Lệnh khoá hiện hành của kỳ (chưa huỷ) cho trường {@code currentLockOrder} của {@code Period}:
   * SCR-0801 hiện người lập, người duyệt và trạng thái lệnh từ trường này (QA-06 HT-23).
   */
  @Transactional(value = TX, readOnly = true)
  public Optional<PeriodLockOrder> current(TenantId tenantId, PeriodId periodId) {
    return orders.active(tenantId, periodId);
  }

  /** Lệnh của kỳ theo mã (để phát lại phản hồi đã lưu). */
  @Transactional(value = TX, readOnly = true)
  public Optional<PeriodLockOrder> find(TenantId tenantId, PeriodId periodId, UUID orderId) {
    return orders.findById(tenantId, orderId).filter(o -> o.periodId().equals(periodId));
  }

  static List<LockStep> sixSteps(List<LockStep> projected) {
    List<LockStep> steps = new ArrayList<>();
    for (int i = 0; i < LockStep.NAMES.size(); i++) {
      int no = i;
      steps.add(
          projected.stream()
              .filter(s -> s.stepNo() == no)
              .findFirst()
              .orElse(LockStep.pending(no)));
    }
    return steps;
  }

  /** {@code CK-<mã kỳ bỏ gạch>}, thêm hậu tố khi kỳ đã có lệnh bị huỷ (BR-08-08). */
  private String idempotencyKey(TenantId tenantId, Period period) {
    String base = "CK-" + period.code().replace("-", "").replace(" ", "");
    if (base.length() > KEY_MAX - 4) {
      base = base.substring(0, KEY_MAX - 4);
    }
    int used = orders.countKeys(tenantId, base);
    return used == 0 ? base : base + "-" + (used + 1);
  }

  private void requireStepUp(Actor actor, String operationId) {
    requireStepUp(actor, operationId, clock);
  }

  /** MFA bước lên còn hạn 15 phút (BR-08-02); dùng chung cho các thao tác điều khiển saga. */
  static void requireStepUp(Actor actor, String operationId, Clock clock) {
    long maxAge = STEP_UP_WINDOW.toSeconds();
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(actor.acr()))) {
      throw new StepUpRequiredException(
          operationId, maxAge, "Thao tác cần xác thực nhiều lớp trong 15 phút gần nhất.");
    }
    long age = clock.instant().getEpochSecond() - (actor.authTime() == null ? 0 : actor.authTime());
    if (actor.authTime() == null || age > maxAge || age < -30) {
      throw new StepUpRequiredException(
          operationId, maxAge, "Phiên xác thực nhiều lớp đã quá 15 phút, cần xác thực lại.");
    }
  }

  private PeriodLockOrder orderOf(TenantId tenantId, PeriodId periodId, UUID orderId) {
    return orders
        .findById(tenantId, orderId)
        .filter(o -> o.periodId().equals(periodId))
        .orElseThrow(() -> new NoSuchElementException("lockOrder"));
  }

  private String periodName(TenantId tenantId, PeriodId periodId) {
    return periods.findById(tenantId, periodId).map(Period::name).orElse("");
  }

  private void save(PeriodLockOrder order, int expectedVersion, Actor actor) {
    if (!orders.update(order, expectedVersion, actor.userId())) {
      throw new VersionConflictException("CONFIG", (long) expectedVersion, null);
    }
  }

  private static void requireVersion(PeriodLockOrder order, long expected) {
    if (expected != order.rowVersion()) {
      throw VersionConflictException.of("CONFIG", expected, order.rowVersion());
    }
  }

  private static BusinessException orderExists() {
    return new BusinessException("PERIOD-409-ORDER-EXISTS", "Kỳ đã có lệnh khoá chưa huỷ.");
  }

  private static BusinessException samePerson(String detail) {
    return new BusinessException("PERIOD-403-SAME-PERSON", detail);
  }
}
