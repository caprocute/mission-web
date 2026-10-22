package vn.viettel.okrkpi.config.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.config.api.PeriodCreatedEvent;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodOpenedEvent;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.application.port.PeriodConflictException;
import vn.viettel.okrkpi.config.application.port.PeriodEvents;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.DefaultPeriodCalendar;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodMilestones;
import vn.viettel.okrkpi.config.domain.PeriodSchedule;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng kỳ đánh giá của {@code MOD-08 · Configuration & Period} (CORE-08-02, QT-08,
 * SCR-0801): tạo, xem, liệt kê kỳ; chuyển trạng thái kỳ (ghi nhật ký và outbox cùng giao dịch);
 * phát sự kiện mở kỳ; và cổng {@link PeriodApi} cho các mô-đun khác, đọc thẳng {@code
 * config.period}.
 */
public class PeriodConfigService implements PeriodApi {

  static final String WARNING_LATE_LOCK =
      "Ngày khoá kỳ dự kiến không sớm hơn hạn báo cáo kết quả đánh giá; cần khoá kỳ sớm hơn để kịp"
          + " đánh giá và báo cáo trước ngày 10 của tháng sau kỳ.";
  private static final String TX = "configTransactionManager";
  private static final Comparator<PeriodKind> CURRENT_PRIORITY =
      Comparator.comparingInt(k -> k == PeriodKind.QUY ? 0 : 1 + k.ordinal());

  private final PeriodRepository periods;
  private final PeriodEvents events;
  private final ApplicationEventPublisher publisher;
  private final PeriodCalendarPort calendar;
  private final Clock clock;
  private final PeriodStateCache states;
  private final TransactionOperations reads;
  private Supplier<String> callerModule = () -> null;

  /**
   * @param calendar tham số lịch của Policy Pack; {@code null} dùng lịch mặc định của TL-02a
   */
  public PeriodConfigService(
      PeriodRepository periods,
      PeriodEvents events,
      ApplicationEventPublisher publisher,
      PeriodCalendarPort calendar,
      Clock clock) {
    this(
        periods,
        events,
        publisher,
        calendar,
        clock,
        new PeriodStateCache(PeriodStateCache.DEFAULT_TTL),
        TransactionOperations.withoutTransaction());
  }

  /**
   * @param states bộ đệm trạng thái kỳ của pod
   * @param reads giao dịch chỉ đọc của {@code configTransactionManager}, chỉ mở khi trượt đệm
   */
  public PeriodConfigService(
      PeriodRepository periods,
      PeriodEvents events,
      ApplicationEventPublisher publisher,
      PeriodCalendarPort calendar,
      Clock clock,
      PeriodStateCache states,
      TransactionOperations reads) {
    this.states = Objects.requireNonNull(states, "states");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.events = Objects.requireNonNull(events, "events");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.calendar = calendar;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Tạo kỳ ở {@code MO} với lịch mốc theo Policy Pack, phát "kỳ đã tạo" để MOD-05 ghim gói; kỳ đã
   * tới ngày bắt đầu thì phát luôn {@link PeriodOpenedEvent}.
   *
   * @throws BusinessException {@code PERIOD-409-OVERLAP} khi chồng lấn kỳ cùng loại, cùng luồng;
   *     {@code COMMON-422-VALIDATION} khi dữ liệu sai
   */
  @Transactional(TX)
  public PeriodView create(TenantId tenantId, UUID actorId, NewPeriod cmd) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(cmd, "cmd");
    if (cmd.startDate() != null
        && cmd.endDate() != null
        && cmd.endDate().isBefore(cmd.startDate())) {
      throw invalid("endDate", "Ngày kết thúc phải không sớm hơn ngày bắt đầu.");
    }
    if (cmd.parentId() != null && !periods.existsById(tenantId, cmd.parentId())) {
      throw invalid("parentPeriodId", "Không tìm thấy kỳ cha.");
    }
    Period period;
    try {
      period =
          Period.builder()
              .id(PeriodId.of(Ids.newUuidV7()))
              .tenantId(tenantId)
              .code(cmd.code())
              .name(cmd.name())
              .kind(cmd.kind())
              .flow(cmd.flow())
              .parentId(cmd.parentId())
              .startDate(cmd.startDate())
              .endDate(cmd.endDate())
              .schedule(
                  scheduleFor(tenantId, cmd)
                      .overriddenBy(cmd.planDeadline(), cmd.submissionDeadline(), cmd.milestones()))
              .created(actorId, clock.instant())
              .build();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw invalid("body", "Thông tin kỳ chưa đầy đủ hoặc chưa hợp lệ.");
    }
    if (periods.overlaps(
        tenantId, period.kind(), period.flow(), period.startDate(), period.endDate())) {
      throw overlap();
    }
    try {
      periods.insert(period);
    } catch (PeriodConflictException e) {
      throw e.kind() == PeriodConflictException.Kind.OVERLAP
          ? overlap()
          : invalid("code", "Mã kỳ đã được dùng cho luồng này.");
    }
    publisher.publishEvent(
        new PeriodCreatedEvent(
            period.id(),
            tenantId,
            period.code(),
            period.kind().name(),
            period.flow().name(),
            period.startDate(),
            period.endDate(),
            period.bucket(),
            period.createdAt()));
    LocalDate today = clock.businessDate();
    if (!period.notStarted(today)) {
      announceOpening(period);
    }
    return view(period, today);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<PeriodView> get(TenantId tenantId, PeriodId periodId) {
    LocalDate today = clock.businessDate();
    return periods.findById(tenantId, periodId).map(p -> view(p, today));
  }

  /** Một trang kỳ, đọc {@code fetchSize} dòng để biết còn trang sau. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<PeriodView> list(
      TenantId tenantId,
      PeriodRepository.Filter filter,
      PeriodRepository.Keyset after,
      int fetchSize) {
    LocalDate today = clock.businessDate();
    return periods.list(tenantId, filter, after, fetchSize).stream()
        .map(p -> view(p, today))
        .toList();
  }

  /**
   * Chuyển trạng thái kỳ, ghi {@code config.period_status_log} và outbox trong cùng giao dịch. Gọi
   * từ saga chốt kỳ (CORE-08-05) và quy trình mở lại hậu kiểm.
   *
   * @param causeRef saga chốt kỳ, saga bù trừ hoặc đề nghị mở lại
   * @param lockOrderId lệnh khoá kỳ khi bù trừ (EVT-26), có thể trống
   * @param reason lý do huỷ khoá khi bù trừ (EVT-26), có thể trống
   * @throws vn.viettel.okrkpi.config.domain.IllegalPeriodTransitionException khi chuyển không hợp
   *     lệ
   * @throws java.util.NoSuchElementException khi không có kỳ
   * @throws java.util.ConcurrentModificationException khi kỳ vừa bị ghi đồng thời
   */
  @Transactional(TX)
  public Period transition(
      TenantId tenantId,
      PeriodId periodId,
      PeriodState target,
      UUID causeRef,
      UUID actorId,
      UUID lockOrderId,
      String reason) {
    Period period = periods.findById(tenantId, periodId).orElseThrow();
    int expected = period.rowVersion();
    PeriodTransition t = period.moveTo(target, Ids.newUuidV7(), causeRef, actorId, clock.instant());
    if (!periods.updateState(period, expected, actorId)) {
      throw new java.util.ConcurrentModificationException("Kỳ vừa được cập nhật đồng thời");
    }
    periods.appendStatusLog(tenantId, t);
    events.statusChanged(period, t, lockOrderId, reason);
    evictNowAndAfterCommit(tenantId, periodId);
    return period;
  }

  /**
   * Phát {@link PeriodOpenedEvent} cho các kỳ bắt đầu hôm qua hoặc hôm nay mà chưa phát (tác vụ
   * định kỳ; ngày hôm qua để bù lượt chạy bị lỡ). Chạy lại nhiều lần, nhiều pod vẫn chỉ phát một
   * lần.
   *
   * @return số kỳ vừa phát
   */
  @Transactional(TX)
  public int announceOpenings(TenantId tenantId) {
    LocalDate today = clock.businessDate();
    int announced = 0;
    for (Period p : periods.startingBetween(tenantId, today.minusDays(1), today)) {
      if (announceOpening(p)) {
        announced++;
      }
    }
    return announced;
  }

  /**
   * Phát {@link PeriodOpenedEvent} cho kỳ đã có ảnh chụp, trong giao dịch để Spring Modulith ghi
   * vào {@code modulith.event_publication}.
   */
  @Transactional
  public void openPeriod(PeriodSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    publisher.publishEvent(
        new PeriodOpenedEvent(
            snapshot.id(), snapshot.tenantId(), snapshot.code(), clock.instant()));
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
    return findCurrent(tenantId, null);
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<PeriodSnapshot> findCurrent(TenantId tenantId, String cadence) {
    Objects.requireNonNull(tenantId, "tenantId");
    PeriodKind kind = cadence == null || cadence.isBlank() ? null : PeriodKind.valueOf(cadence);
    return periods.covering(tenantId, kind, clock.businessDate()).stream()
        .sorted(
            Comparator.comparing(Period::kind, CURRENT_PRIORITY)
                .thenComparing(Period::startDate, Comparator.reverseOrder()))
        .findFirst()
        .map(PeriodConfigService::snapshot);
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<PeriodSnapshot> current(TenantId tenantId) {
    return findCurrent(tenantId, null);
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public PeriodSnapshot current(TenantId tenantId, String cadence) {
    return findCurrent(tenantId, cadence).orElseThrow();
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    return periods.findById(tenantId, periodId).map(PeriodConfigService::snapshot);
  }

  /** Chặn khi kỳ đã khoá ({@code DA_KHOA}); kỳ không tồn tại không chặn. Đọc qua bộ đệm. */
  @Override
  public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {
    Optional<PeriodStateCache.Entry> e = state(tenantId, periodId);
    if (e.isPresent() && e.get().state() == PeriodState.DA_KHOA) {
      throw new PeriodLockedException(periodId, e.get().name(), callerModule.get());
    }
  }

  /**
   * TL-01: chặn khi kỳ ở {@code DANG_KHOA} hoặc {@code DA_KHOA} (CORE-08-03). Ở task này {@code
   * MO_LAI_HAU_KIEM} cũng bị chặn; phạm vi được mở làm ở CORE-08-06. Kỳ không tồn tại không chặn.
   * Đọc qua bộ đệm trạng thái kỳ, chỉ mở giao dịch đọc khi trượt đệm.
   */
  @Override
  public void assertWritable(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {
    Optional<PeriodStateCache.Entry> e = state(tenantId, periodId);
    if (e.isPresent() && !e.get().state().writable()) {
      throw new PeriodLockedException(periodId, e.get().name(), callerModule.get());
    }
  }

  /**
   * Nguồn mô-đun nhận lệnh để chọn mã lỗi {@code <MODULE>-409-PERIOD-LOCKED}; mặc định không xác
   * định (mã của MOD-04).
   */
  public void setCallerModule(Supplier<String> callerModule) {
    this.callerModule = Objects.requireNonNull(callerModule, "callerModule");
  }

  /** Xoá đệm trạng thái của kỳ (khi pod khác báo kỳ đổi trạng thái qua EVT-09, EVT-26). */
  public void evictState(TenantId tenantId, PeriodId periodId) {
    states.evict(tenantId, periodId);
  }

  /** Xoá toàn bộ đệm trạng thái kỳ của pod. */
  public void evictAllStates() {
    states.evictAll();
  }

  private Optional<PeriodStateCache.Entry> state(TenantId tenantId, PeriodId periodId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    return states.get(
        tenantId,
        periodId,
        () ->
            reads.execute(
                s ->
                    periods
                        .findById(tenantId, periodId)
                        .map(p -> new PeriodStateCache.Entry(p.state(), p.name()))));
  }

  private void evictNowAndAfterCommit(TenantId tenantId, PeriodId periodId) {
    states.evict(tenantId, periodId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              states.evict(tenantId, periodId);
            }
          });
    }
  }

  private boolean announceOpening(Period p) {
    if (!periods.markOpeningAnnounced(p.tenantId(), p.id())) {
      return false;
    }
    publisher.publishEvent(new PeriodOpenedEvent(p.id(), p.tenantId(), p.code(), clock.instant()));
    return true;
  }

  private PeriodSchedule scheduleFor(TenantId tenantId, NewPeriod cmd) {
    if (cmd.startDate() == null || cmd.endDate() == null || cmd.kind() == null) {
      throw new IllegalArgumentException("Thiếu ngày hoặc loại kỳ");
    }
    PeriodSchedule fallback = DefaultPeriodCalendar.scheduleOf(cmd.startDate(), cmd.endDate());
    if (calendar == null || cmd.flow() == null) {
      return fallback;
    }
    return calendar
        .calendarFor(tenantId, cmd.kind().name(), cmd.flow().name(), cmd.startDate(), cmd.endDate())
        .map(
            c ->
                fallback.overriddenBy(
                    c.planApprovalDeadline(),
                    c.submissionDeadline(),
                    new PeriodMilestones(
                        c.planApprovalDeadline(),
                        c.evidenceDeadline(),
                        c.acceptanceBacklogDeadline(),
                        c.plannedLockDate())))
        .orElse(fallback);
  }

  private static PeriodView view(Period p, LocalDate today) {
    List<String> warnings = new ArrayList<>();
    if (p.schedule().lockTooLate()) {
      warnings.add(WARNING_LATE_LOCK);
    }
    return new PeriodView(p, p.notStarted(today), warnings);
  }

  static PeriodSnapshot snapshot(Period p) {
    return new PeriodSnapshot(
        p.id(),
        p.tenantId(),
        p.code(),
        p.name(),
        p.startDate(),
        p.endDate(),
        PeriodStatus.valueOf(p.state().name()));
  }

  private static BusinessException overlap() {
    return new BusinessException(
        "PERIOD-409-OVERLAP", "Đã có kỳ cùng loại, cùng luồng trùng khoảng thời gian này.");
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION",
        "Thông tin kỳ chưa hợp lệ.",
        List.of(ProblemFieldError.of(field, message)));
  }
}
