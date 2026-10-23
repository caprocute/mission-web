package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.LockOrderRepository;
import vn.viettel.okrkpi.config.application.port.LockProjectionRepository;
import vn.viettel.okrkpi.config.application.port.PeriodLockSagaControl;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.application.port.RoleHolders;
import vn.viettel.okrkpi.config.domain.LockBatch;
import vn.viettel.okrkpi.config.domain.LockCancelRequest;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.LockEvent;
import vn.viettel.okrkpi.config.domain.LockFailure;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockSagaDiagram;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điều khiển và theo dõi saga chốt kỳ của MOD-08 (CORE-08-05 phần còn lại, QT-08 bước 9, SCR-0801):
 * tạm dừng, tiếp tục, chạy lại hồ sơ lỗi, đề nghị huỷ và duyệt thứ hai huỷ; đọc lô tính điểm, hồ sơ
 * lỗi, nhật ký sự kiện, sơ đồ BPMN và tiến trình đầy đủ.
 *
 * <p>Ranh giới với SVC-06: MOD-08 kiểm trạng thái lệnh và kỳ, bốn mắt khi huỷ (BR-08-09), MFA bước
 * lên 15 phút (BR-08-02), ghi cờ tạm dừng và đề nghị huỷ vào {@code config.period_lock_order}, ghi
 * nhật ký và sổ cái (BR-08-10, {@code sys.audit.v1} qua outbox cùng giao dịch) rồi chuyển lệnh qua
 * {@link PeriodLockSagaControl}. Việc thật sự dừng, chạy lại, bù trừ là của saga ở SVC-06
 * (DV-06-06, DV-06-07); saga bù trừ xong thì gọi {@link #completeCancel} để kỳ trở về {@code MO}
 * (EVT-26).
 *
 * <p>Điểm không quay lại theo hợp đồng và TL-02a: huỷ chỉ được trước khi bước 4 (ký số lô) hoàn
 * tất.
 */
public class PeriodLockControlService {

  static final String TX = PeriodLockService.TX;
  static final String SECOND_APPROVER = "second_approver";
  static final String OBJECT_TYPE = "config.period_lock_order";
  static final String KIND_STEP = "BUOC_SAGA";
  private static final int SIGNING_STEP = 4;
  private static final int SCORING_STEP = 2;
  private static final int REASON_MIN = 20;
  private static final int REASON_MAX = 2000;
  private static final int COMMENT_MAX = 1000;
  private static final int RETRY_MAX = 1000;

  /**
   * Trả kỳ {@code DANG_KHOA} về {@code MO} và phát EVT-26 (nối tới {@code PeriodConfigService}).
   */
  @FunctionalInterface
  public interface PeriodReverter {
    void revert(
        TenantId tenantId,
        PeriodId periodId,
        UUID sagaId,
        UUID actorId,
        UUID lockOrderId,
        String reason);
  }

  /** Tổng số hồ sơ của tiến trình. */
  public record Totals(int profiles, int scored, int failed) {}

  /** Tiến trình đầy đủ cho {@code getPeriodLockProgress} và phản hồi các lệnh điều khiển. */
  public record Overview(
      PeriodLockOrder order,
      List<LockStep> steps,
      Totals totals,
      int excludedEvidence,
      List<String> warnings) {

    public Overview {
      steps = List.copyOf(steps);
      warnings = List.copyOf(warnings);
    }

    /** Còn huỷ được: saga đang chạy, chưa bù trừ và bước 4 chưa hoàn tất (BR-08-09). */
    public boolean cancellable() {
      return order.controllable() && !steps.get(SIGNING_STEP).done();
    }

    /** Trạng thái saga theo lược đồ {@code SagaStatus} của hợp đồng (suy ra từ bản chiếu). */
    public String sagaStatus() {
      if (order.status() == LockOrderStatus.DA_HUY) {
        return "DA_BU_TRU";
      }
      if (order.status() == LockOrderStatus.HOAN_TAT) {
        return "HOAN_TAT";
      }
      LockCancelRequest cancel = order.cancelRequest();
      if (cancel != null && cancel.approved()) {
        return "DANG_BU_TRU";
      }
      if (steps.stream().anyMatch(s -> "LOI".equals(s.status()))) {
        return "LOI_CAN_XU_LY";
      }
      if (order.pauseRequested() && steps.stream().noneMatch(s -> "DANG_CHAY".equals(s.status()))) {
        return "CHO_SU_KIEN";
      }
      return "DANG_CHAY";
    }
  }

  /** Lệnh đã nhận, trả 202 với mã tác vụ. */
  public record Accepted(PeriodLockOrder order, UUID jobId, String jobType, Instant at) {}

  private final LockOrderRepository orders;
  private final LockProjectionRepository projections;
  private final PeriodRepository periods;
  private final PeriodLockSagaControl saga;
  private final LockOrderNotices notices;
  private final RoleHolders roles;
  private final ConfigAudit audit;
  private final PeriodReverter reverter;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PeriodLockControlService(
      LockOrderRepository orders,
      LockProjectionRepository projections,
      PeriodRepository periods,
      PeriodLockSagaControl saga,
      LockOrderNotices notices,
      RoleHolders roles,
      ConfigAudit audit,
      PeriodReverter reverter,
      Clock clock) {
    this.orders = Objects.requireNonNull(orders, "orders");
    this.projections = Objects.requireNonNull(projections, "projections");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.saga = Objects.requireNonNull(saga, "saga");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.roles = Objects.requireNonNull(roles, "roles");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.reverter = Objects.requireNonNull(reverter, "reverter");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // --- Đọc ----------------------------------------------------------------------------------

  /** Tiến trình của lệnh mới nhất đã có saga; rỗng khi kỳ chưa có saga. */
  @Transactional(value = TX, readOnly = true)
  public Optional<Overview> overview(TenantId tenantId, PeriodId periodId) {
    return current(tenantId, periodId).map(o -> overviewOf(tenantId, o));
  }

  /** {@code listPeriodLockBatches}. */
  @Transactional(value = TX, readOnly = true)
  public List<LockBatch> batches(
      TenantId tenantId, PeriodId periodId, String status, UUID afterId, int fetchSize) {
    return projections.batches(
        tenantId, require(tenantId, periodId).id(), status, afterId, fetchSize);
  }

  /** {@code listPeriodLockFailures}. */
  @Transactional(value = TX, readOnly = true)
  public List<LockFailure> failures(
      TenantId tenantId, PeriodId periodId, UUID afterProfileId, int fetchSize) {
    return projections.openFailures(
        tenantId, require(tenantId, periodId).id(), afterProfileId, fetchSize);
  }

  /** {@code listPeriodLockEvents}: mới nhất trước. */
  @Transactional(value = TX, readOnly = true)
  public List<LockEvent> events(
      TenantId tenantId, PeriodId periodId, Instant beforeAt, UUID beforeId, int fetchSize) {
    return projections.events(
        tenantId, require(tenantId, periodId).id(), beforeAt, beforeId, fetchSize);
  }

  /** {@code getPeriodLockDiagram}: sơ đồ dựng từ sáu bước đã chiếu. */
  @Transactional(value = TX, readOnly = true)
  public Optional<LockSagaDiagram> diagram(TenantId tenantId, PeriodId periodId) {
    return current(tenantId, periodId)
        .map(o -> LockSagaDiagram.of(PeriodLockService.sixSteps(orders.steps(tenantId, o.id()))));
  }

  /** Lệnh trong hộp lệnh theo mã tác vụ (nguồn của {@code /api/v1/jobs/{id}}). */
  @Transactional(value = TX, readOnly = true)
  public Optional<LockProjectionRepository.CommandRow> command(TenantId tenantId, UUID jobId) {
    return projections.command(tenantId, jobId);
  }

  // --- Điều khiển ---------------------------------------------------------------------------

  /** {@code pausePeriodLock}: đặt cờ tạm dừng sau bước hiện tại; đã đặt thì giữ nguyên. */
  @Transactional(TX)
  public Overview pause(
      TenantId tenantId, Actor actor, PeriodId periodId, long expectedVersion, String comment) {
    PeriodLockOrder order = requireLocked(tenantId, periodId);
    requireRunning(order);
    PeriodLockService.requireStepUp(actor, "pausePeriodLock", clock);
    requireVersion(order, expectedVersion);
    int before = order.rowVersion();
    if (order.requestPause(actor.userId(), clock.instant())) {
      save(order, before, actor);
      control(order, LockCommand.TAM_DUNG, List.of(), comment, actor, "TAM_DUNG_KHOA_KY");
    }
    return overviewOf(tenantId, order);
  }

  /** {@code resumePeriodLock}: gỡ cờ tạm dừng; chưa tạm dừng thì giữ nguyên. */
  @Transactional(TX)
  public Overview resume(
      TenantId tenantId, Actor actor, PeriodId periodId, long expectedVersion, String comment) {
    PeriodLockOrder order = requireLocked(tenantId, periodId);
    requireRunning(order);
    PeriodLockService.requireStepUp(actor, "resumePeriodLock", clock);
    requireVersion(order, expectedVersion);
    int before = order.rowVersion();
    if (order.resume()) {
      save(order, before, actor);
      control(order, LockCommand.TIEP_TUC, List.of(), comment, actor, "TIEP_TUC_KHOA_KY");
    }
    return overviewOf(tenantId, order);
  }

  /**
   * {@code retryFailedPeriodLock}: tạo lô mới cho hồ sơ trong hàng đợi lỗi (hoặc các hồ sơ chỉ định
   * còn trong hàng đợi); không có hồ sơ nào trả {@code PERIOD-409-NO-FAILURE}.
   */
  @Transactional(TX)
  public Accepted retryFailed(
      TenantId tenantId, Actor actor, PeriodId periodId, List<UUID> profileIds) {
    PeriodLockOrder order = requireLocked(tenantId, periodId);
    requireRunning(order);
    PeriodLockService.requireStepUp(actor, "retryFailedPeriodLock", clock);
    List<UUID> requested =
        profileIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(profileIds));
    if (requested.size() > RETRY_MAX) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Mỗi lần chạy lại tối đa 1.000 hồ sơ được chỉ định.");
    }
    List<UUID> targets =
        requested.isEmpty()
            ? List.of()
            : projections.openFailuresAmong(tenantId, order.id(), requested);
    int count =
        requested.isEmpty() ? projections.countOpenFailures(tenantId, order.id()) : targets.size();
    if (count == 0) {
      throw new BusinessException("PERIOD-409-NO-FAILURE", "Không có hồ sơ lỗi nào cần chạy lại.");
    }
    UUID jobId =
        control(
            order,
            LockCommand.CHAY_LAI_LOI,
            targets,
            "Số hồ sơ chạy lại: " + count + ".",
            actor,
            "CHAY_LAI_HO_SO_LOI_KHOA_KY");
    return new Accepted(order, jobId, LockCommand.CHAY_LAI_LOI.jobType(), clock.instant());
  }

  /**
   * {@code requestPeriodLockCancel}: tạo đề nghị huỷ và bù trừ, gửi người duyệt thứ hai qua EVT-21
   * (MSG-08-05). Quá bước 4 trả {@code PERIOD-409-POINT-OF-NO-RETURN}; đã có đề nghị chờ duyệt thì
   * giữ nguyên.
   */
  @Transactional(TX)
  public Overview requestCancel(
      TenantId tenantId, Actor actor, PeriodId periodId, long expectedVersion, String reason) {
    PeriodLockOrder order = requireLocked(tenantId, periodId);
    requireBeforePointOfNoReturn(tenantId, order);
    requireRunning(order);
    String text = reason == null ? "" : reason.strip();
    if (text.length() < REASON_MIN || text.length() > REASON_MAX) {
      throw new BusinessException("COMMON-422-VALIDATION", "Lý do huỷ cần từ 20 đến 2.000 ký tự.");
    }
    PeriodLockService.requireStepUp(actor, "requestPeriodLockCancel", clock);
    requireVersion(order, expectedVersion);
    int before = order.rowVersion();
    if (order.requestCancel(actor.userId(), text, clock.instant())) {
      save(order, before, actor);
      log(
          order,
          "DE_NGHI_HUY",
          "Đề nghị huỷ và bù trừ toàn bộ, chờ người duyệt thứ hai.",
          null,
          actor.userId());
      audit.changed(
          tenantId,
          actor.userId(),
          "DE_NGHI_HUY_KHOA_KY",
          OBJECT_TYPE,
          order.id().toString(),
          order.id(),
          order.rowVersion());
      List<UUID> approvers =
          new ArrayList<>(roles.holders(tenantId, SECOND_APPROVER, clock.instant()));
      approvers.remove(actor.userId());
      notices.cancelRequested(order, periodName(tenantId, order), approvers);
    }
    return overviewOf(tenantId, order);
  }

  /**
   * {@code approvePeriodLockCancel}: người duyệt thứ hai, khác người đề nghị, duyệt huỷ; lệnh bù
   * trừ chuyển xuống saga, trả tác vụ {@code PERIOD_UNLOCK}. Kỳ về {@code MO} khi saga bù trừ xong
   * ({@link #completeCancel}).
   */
  @Transactional(TX)
  public Accepted approveCancel(
      TenantId tenantId, Actor actor, PeriodId periodId, long expectedVersion, String comment) {
    PeriodLockOrder order = requireLocked(tenantId, periodId);
    LockCancelRequest cancel = order.cancelRequest();
    if (cancel == null || cancel.approved()) {
      throw new BusinessException(
          "CONFIG-409-NOT-PENDING", "Không có đề nghị huỷ và bù trừ đang chờ duyệt.");
    }
    requireBeforePointOfNoReturn(tenantId, order);
    requireRunning(order);
    if (actor.userId().equals(cancel.requestedBy())) {
      throw new BusinessException(
          "PERIOD-403-SAME-PERSON", "Người duyệt huỷ phải khác người đề nghị huỷ.");
    }
    PeriodLockService.requireStepUp(actor, "approvePeriodLockCancel", clock);
    requireVersion(order, expectedVersion);
    int before = order.rowVersion();
    order.approveCancel(actor.userId(), clock.instant());
    save(order, before, actor);
    UUID jobId = control(order, LockCommand.BU_TRU, List.of(), comment, actor, "DUYET_HUY_KHOA_KY");
    return new Accepted(order, jobId, LockCommand.BU_TRU.jobType(), clock.instant());
  }

  /**
   * Saga đã bù trừ toàn bộ theo đề nghị huỷ đã duyệt (gọi từ bộ nhận phản hồi saga, DV-06-07): lệnh
   * {@code DA_HUY}; kỳ {@code DANG_KHOA} trở về {@code MO} và phát EVT-26; tác vụ {@code
   * PERIOD_UNLOCK} hoàn tất; EVT-21 MSG-08-08 cho người đề nghị và quản trị tenant. Gọi lại khi
   * lệnh đã huỷ không làm gì.
   *
   * @return {@code false} khi lệnh đã huỷ từ trước
   */
  @Transactional(TX)
  public boolean completeCancel(TenantId tenantId, UUID lockOrderId, UUID actorId) {
    PeriodLockOrder order =
        orders
            .lockById(tenantId, lockOrderId)
            .orElseThrow(() -> new NoSuchElementException("lockOrder"));
    if (order.status() == LockOrderStatus.DA_HUY) {
      return false;
    }
    int before = order.rowVersion();
    order.cancelled();
    if (!orders.update(order, before, actorId)) {
      throw new VersionConflictException("CONFIG", (long) before, null);
    }
    Period period = periods.findById(tenantId, order.periodId()).orElseThrow();
    String evt = null;
    if (period.state() == PeriodState.DANG_KHOA) {
      reverter.revert(
          tenantId,
          order.periodId(),
          order.sagaId(),
          actorId,
          order.id(),
          order.cancelRequest().reason());
      evt = "EVT-26";
    }
    Instant now = clock.instant();
    projections.finishCommands(tenantId, order.id(), LockCommand.BU_TRU, "HOAN_TAT", now);
    log(
        order,
        KIND_STEP,
        "Đã bù trừ toàn bộ, kỳ " + period.name() + " trở về trạng thái mở.",
        evt,
        null);
    audit.changed(
        tenantId,
        actorId,
        "HOAN_TAT_BU_TRU_KHOA_KY",
        OBJECT_TYPE,
        order.id().toString(),
        order.id(),
        order.rowVersion());
    LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
    recipients.add(order.cancelRequest().requestedBy());
    recipients.addAll(roles.holders(tenantId, PeriodLockService.TENANT_ADMIN, now));
    notices.cancelCompleted(order, period.name(), new ArrayList<>(recipients));
    return true;
  }

  // --- Nội bộ -------------------------------------------------------------------------------

  private Overview overviewOf(TenantId tenantId, PeriodLockOrder order) {
    List<LockStep> steps = PeriodLockService.sixSteps(orders.steps(tenantId, order.id()));
    LockStep scoring = steps.get(SCORING_STEP);
    LockProjectionRepository.BatchTotals batch = projections.batchTotals(tenantId, order.id());
    int openFailures = projections.countOpenFailures(tenantId, order.id());
    Totals totals =
        batch.batches() > 0
            ? new Totals(batch.profiles(), batch.scored(), openFailures)
            : new Totals(
                intOf(scoring.resultSummary().get("profiles")),
                intOf(scoring.resultSummary().get("scored")),
                Math.max(openFailures, intOf(scoring.resultSummary().get("failed"))));
    LockStep precondition = steps.get(0);
    int excluded = intOf(precondition.resultSummary().get("excludedEvidence"));
    List<String> warnings = new ArrayList<>();
    if (excluded > 0) {
      warnings.add(
          "Có " + excluded + " minh chứng chưa được nghiệm thu sẽ không được tính điểm trong kỳ.");
    }
    if (precondition.resultSummary().get("warnings") instanceof Collection<?> extra) {
      extra.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .forEach(warnings::add);
    }
    if (totals.failed() > 0 && order.status() == LockOrderStatus.DANG_CHAY) {
      warnings.add("Có " + totals.failed() + " hồ sơ lỗi khi tính điểm đang chờ xử lý.");
    }
    LockCancelRequest cancel = order.cancelRequest();
    if (order.status() == LockOrderStatus.DANG_CHAY) {
      if (cancel != null && cancel.approved()) {
        warnings.add("Đang huỷ và bù trừ toàn bộ theo đề nghị đã được duyệt.");
      } else if (cancel != null) {
        warnings.add("Đề nghị huỷ và bù trừ đang chờ người duyệt thứ hai.");
      }
      if (order.pauseRequested()) {
        warnings.add("Đã đặt tạm dừng; tiến trình sẽ dừng khi bước hiện tại xong.");
      }
    }
    return new Overview(order, steps, totals, excluded, warnings);
  }

  private UUID control(
      PeriodLockOrder order,
      LockCommand command,
      List<UUID> profileIds,
      String comment,
      Actor actor,
      String action) {
    String note = comment == null ? null : comment.strip();
    if (note != null && note.length() > COMMENT_MAX) {
      throw new BusinessException("COMMON-422-VALIDATION", "Ghi chú không quá 1.000 ký tự.");
    }
    UUID jobId = saga.send(order, command, profileIds, note, actor.userId());
    String text =
        note == null || note.isEmpty() ? command.label() + "." : command.label() + ". " + note;
    log(order, command.name(), text, null, actor.userId());
    audit.changed(
        order.tenantId(),
        actor.userId(),
        action,
        OBJECT_TYPE,
        order.id().toString(),
        order.id(),
        order.rowVersion());
    return jobId;
  }

  private void log(PeriodLockOrder order, String kind, String text, String evt, UUID actorId) {
    projections.appendEvent(
        order.tenantId(),
        order.id(),
        new LockEvent(Ids.newUuidV7(), clock.instant(), kind, text, evt, actorId, null));
  }

  private Optional<PeriodLockOrder> current(TenantId tenantId, PeriodId periodId) {
    return orders.latest(tenantId, periodId).filter(o -> o.sagaId() != null);
  }

  private PeriodLockOrder require(TenantId tenantId, PeriodId periodId) {
    return current(tenantId, periodId).orElseThrow(() -> new NoSuchElementException("lockSaga"));
  }

  private PeriodLockOrder requireLocked(TenantId tenantId, PeriodId periodId) {
    PeriodLockOrder latest = require(tenantId, periodId);
    return orders
        .lockById(tenantId, latest.id())
        .orElseThrow(() -> new NoSuchElementException("lockSaga"));
  }

  private static void requireRunning(PeriodLockOrder order) {
    if (!order.controllable()) {
      throw new BusinessException(
          "PERIOD-409-SAGA-NOT-RUNNING", "Tiến trình khoá kỳ không còn chạy.");
    }
  }

  private void requireBeforePointOfNoReturn(TenantId tenantId, PeriodLockOrder order) {
    boolean signed =
        order.status() == LockOrderStatus.HOAN_TAT
            || (order.status() == LockOrderStatus.DANG_CHAY
                && PeriodLockService.sixSteps(orders.steps(tenantId, order.id()))
                    .get(SIGNING_STEP)
                    .done());
    if (signed) {
      throw new BusinessException(
          "PERIOD-409-POINT-OF-NO-RETURN",
          "Đã ký số lô nên không thể huỷ và bù trừ; cần mở lại hậu kiểm.");
    }
  }

  private String periodName(TenantId tenantId, PeriodLockOrder order) {
    return periods.findById(tenantId, order.periodId()).map(Period::name).orElse("");
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

  private static int intOf(Object v) {
    return v instanceof Number n ? Math.max(0, n.intValue()) : 0;
  }
}
