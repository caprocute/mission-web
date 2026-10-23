package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodReopenApi;
import vn.viettel.okrkpi.config.api.PeriodReopenExpiredEvent;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.config.application.port.PeriodRelockSaga;
import vn.viettel.okrkpi.config.application.port.PeriodReopenRepository;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodReopen;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.ReopenScope;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mở lại hậu kiểm theo phạm vi và tự khoá lại của MOD-08 (CORE-08-06, QT-13 bước 8 và 12,
 * BR-13-05).
 *
 * <ul>
 *   <li>Nhận EVT-22: kỳ {@code DA_KHOA} sang {@code MO_LAI_HAU_KIEM} (tăng {@code reopen_count},
 *       nhật ký nguyên nhân {@code MO_LAI_HAU_KIEM} với mã yêu cầu mở lại), lưu phạm vi và hạn mở.
 *   <li>Hết hạn: báo MOD-07 bằng {@link PeriodReopenExpiredEvent}, khởi chạy saga khoá lại phạm vi
 *       hẹp ở SVC-06, hoặc khoá lại ngay khi cell chưa có saga này.
 *   <li>Khoá lại xong: kỳ về {@code DA_KHOA} (nguyên nhân {@code KHOA_LAI}), EVT-09 qua outbox.
 * </ul>
 */
public class PeriodReopenService implements PeriodReopenApi {

  static final String TX = "configTransactionManager";

  /** Hạn mở mặc định khi EVT-22 chưa mang hạn (suy luận, wiki trang 14). */
  public static final int DEFAULT_OPEN_WORKING_DAYS = 10;

  private static final Logger log = LoggerFactory.getLogger(PeriodReopenService.class);

  private final PeriodReopenRepository reopens;
  private final PeriodRepository periods;
  private final PeriodConfigService transitions;
  private final WorkingCalendarApi calendar;
  private final PeriodRelockSaga saga;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final int openWorkingDays;

  /**
   * @param saga saga khoá lại ở SVC-06; {@code null} thì khoá lại ngay trong MOD-08
   * @param openWorkingDays số ngày làm việc được mở khi sự kiện không mang hạn
   */
  public PeriodReopenService(
      PeriodReopenRepository reopens,
      PeriodRepository periods,
      PeriodConfigService transitions,
      WorkingCalendarApi calendar,
      PeriodRelockSaga saga,
      ApplicationEventPublisher publisher,
      Clock clock,
      int openWorkingDays) {
    this.reopens = Objects.requireNonNull(reopens, "reopens");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.saga = saga;
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (openWorkingDays < 1) {
      throw new IllegalArgumentException("Số ngày mở lại phải từ 1");
    }
    this.openWorkingDays = openWorkingDays;
  }

  /**
   * Xử lý EVT-22. Gọi trong giao dịch inbox của schema config nên nhận trùng bị bỏ qua từ inbox;
   * bản chiếu theo mã yêu cầu là lớp khử trùng thứ hai.
   *
   * @param openUntil hạn mở lấy từ sự kiện; {@code null} thì tính bằng số ngày làm việc mặc định
   * @return {@code true} khi kỳ vừa được mở lại
   */
  @Transactional(TX)
  public boolean onReopened(
      TenantId tenantId,
      PeriodId periodId,
      UUID reopenRequestId,
      ReopenScope scope,
      Instant executedAt,
      Instant openUntil,
      UUID actorId) {
    Objects.requireNonNull(reopenRequestId, "reopenRequestId");
    if (reopens.findById(tenantId, reopenRequestId).isPresent()) {
      return false;
    }
    Period period = periods.findById(tenantId, periodId).orElseThrow();
    if (period.state() != PeriodState.DA_KHOA) {
      log.warn(
          "Bỏ qua EVT-22 cho kỳ {} đang ở {} (chỉ mở lại kỳ đã khoá)", periodId, period.state());
      return false;
    }
    Instant opened = executedAt == null ? clock.instant() : executedAt;
    Instant until = openUntil == null ? defaultOpenUntil(tenantId, opened) : openUntil;
    PeriodReopen reopen =
        PeriodReopen.open(reopenRequestId, tenantId, periodId, scope, opened, until);
    if (!reopens.insertIfAbsent(reopen, actorId)) {
      return false;
    }
    transitions.transition(
        tenantId, periodId, PeriodState.MO_LAI_HAU_KIEM, reopenRequestId, actorId, null, null);
    return true;
  }

  /**
   * Timer hết hạn: với mỗi lần mở lại quá hạn chưa khoá lại, báo MOD-07 rồi khoá lại. Chạy lại
   * nhiều lần, nhiều pod vẫn chỉ xử lý một lần mỗi lần mở lại.
   *
   * @return số lần mở lại vừa bắt đầu khoá lại
   */
  @Transactional(TX)
  public int expireDue(TenantId tenantId, UUID actorId) {
    Instant now = clock.instant();
    int started = 0;
    for (PeriodReopen r : reopens.dueForRelock(tenantId, now, 100)) {
      if (!reopens.markRelockStarted(tenantId, r.id(), now, actorId)) {
        continue;
      }
      publisher.publishEvent(
          new PeriodReopenExpiredEvent(tenantId, r.periodId(), r.id(), r.openUntil()));
      relock(r, actorId);
      started++;
    }
    return started;
  }

  /**
   * Hậu kiểm hoàn tất trước hạn (MOD-07 báo): bắt đầu khoá lại như khi hết hạn nhưng không báo
   * "chưa hoàn tất".
   *
   * @return {@code false} khi lần mở lại đã bắt đầu khoá lại từ trước
   */
  @Transactional(TX)
  public boolean finish(TenantId tenantId, UUID reopenRequestId, UUID actorId) {
    PeriodReopen r = reopens.findById(tenantId, reopenRequestId).orElseThrow();
    if (!reopens.markRelockStarted(tenantId, r.id(), clock.instant(), actorId)) {
      return false;
    }
    relock(r, actorId);
    return true;
  }

  /** MOD-07 báo hậu kiểm xong trước hạn (CORE-07-09); chưa nhận EVT-22 thì không làm gì. */
  @Override
  @Transactional(TX)
  public boolean finishEarly(TenantId tenantId, UUID reopenRequestId, UUID actorId) {
    if (reopens.findById(tenantId, reopenRequestId).isEmpty()) {
      return false;
    }
    return finish(tenantId, reopenRequestId, actorId);
  }

  /**
   * Saga khoá lại xong: kỳ về {@code DA_KHOA} với nguyên nhân {@code KHOA_LAI}, phát EVT-09.
   * Idempotent.
   *
   * @return {@code false} khi đã khoá lại từ trước
   */
  @Transactional(TX)
  public boolean completeRelock(TenantId tenantId, UUID reopenRequestId, UUID actorId) {
    PeriodReopen r = reopens.findById(tenantId, reopenRequestId).orElseThrow();
    if (!reopens.markRelocked(tenantId, r.id(), clock.instant(), actorId)) {
      return false;
    }
    transitions.transition(
        tenantId, r.periodId(), PeriodState.DA_KHOA, r.id(), actorId, null, null);
    return true;
  }

  /** Các lần mở lại quá hạn, phục vụ giám sát. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<PeriodReopen> due(TenantId tenantId) {
    return reopens.dueForRelock(tenantId, clock.instant(), 100);
  }

  private void relock(PeriodReopen r, UUID actorId) {
    if (saga == null) {
      completeRelock(r.tenantId(), r.id(), actorId);
    } else {
      saga.start(r, actorId);
    }
  }

  /** Cuối ngày làm việc thứ {@code openWorkingDays} kể từ ngày mở, giờ nghiệp vụ. */
  private Instant defaultOpenUntil(TenantId tenantId, Instant opened) {
    LocalDate day = LocalDate.ofInstant(opened, Clock.BUSINESS_ZONE);
    LocalDate last = calendar.addWorkingDays(tenantId, day, openWorkingDays);
    return last.plusDays(1).atStartOfDay(Clock.BUSINESS_ZONE).toInstant();
  }
}
