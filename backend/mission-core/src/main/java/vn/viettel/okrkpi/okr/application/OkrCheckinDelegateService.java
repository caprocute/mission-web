package vn.viettel.okrkpi.okr.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository.Delegation;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Uỷ quyền check-in thay (CORE-01-12, QT-03 "Chủ sở hữu KR vắng mặt dài ngày", SCR-0301): chủ sở
 * hữu KR giao cho một người check-in thay trong thời hạn, thu hồi được bất cứ lúc nào. Mỗi KR một
 * uỷ quyền mở; giao mới thì lần giao cũ được thu hồi. Lịch sử giữ ở {@code
 * okr.kr_checkin_delegate}.
 */
public class OkrCheckinDelegateService {

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  static final int REASON_MAX = 500;

  private final OkrCheckinRepository checkins;
  private final OkrCheckinDelegateRepository delegates;
  private final PeriodApi periods;
  private final Clock clock;

  public OkrCheckinDelegateService(
      OkrCheckinRepository checkins,
      OkrCheckinDelegateRepository delegates,
      PeriodApi periods,
      Clock clock) {
    this.checkins = Objects.requireNonNull(checkins, "checkins");
    this.delegates = Objects.requireNonNull(delegates, "delegates");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Giao quyền check-in thay; trả phiên bản mới của KR. */
  @Transactional(transactionManager = TX)
  public long setDelegate(
      TenantId tenant,
      UUID actor,
      UUID keyResultId,
      long expectedVersion,
      UUID person,
      LocalDate validUntil,
      String reason) {
    CheckinTarget target = lockOwned(tenant, actor, keyResultId, expectedVersion);
    if (person == null || person.equals(target.owner())) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Người được check-in thay phải khác chủ sở hữu kết quả.");
    }
    if (validUntil != null && validUntil.isBefore(LocalDate.now(clock))) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Hạn uỷ quyền không được trước ngày hôm nay.");
    }
    if (reason != null && reason.length() > REASON_MAX) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Lý do uỷ quyền không vượt quá 500 ký tự.");
    }
    delegates.open(tenant, keyResultId).ifPresent(d -> delegates.end(tenant, d.id(), actor));
    delegates.appoint(
        tenant,
        keyResultId,
        person,
        validUntil,
        reason == null || reason.isBlank() ? null : reason.strip(),
        actor);
    return delegates.touchKeyResult(
        tenant, target.periodBucket(), keyResultId, target.rowVersion(), actor);
  }

  /** Thu hồi quyền check-in thay; trả phiên bản mới của KR. */
  @Transactional(transactionManager = TX)
  public long revokeDelegate(TenantId tenant, UUID actor, UUID keyResultId, long expectedVersion) {
    CheckinTarget target = lockOwned(tenant, actor, keyResultId, expectedVersion);
    Delegation open =
        delegates
            .open(tenant, keyResultId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        MODULE + "-409-INVALID-STATE", "Kết quả chưa có người được check-in thay"));
    delegates.end(tenant, open.id(), actor);
    return delegates.touchKeyResult(
        tenant, target.periodBucket(), keyResultId, target.rowVersion(), actor);
  }

  /** Người đang được check-in thay KR hôm nay. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<Delegation> delegateOf(TenantId tenant, UUID keyResultId) {
    return delegates.open(tenant, keyResultId).filter(d -> d.validOn(LocalDate.now(clock)));
  }

  private CheckinTarget lockOwned(
      TenantId tenant, UUID actor, UUID keyResultId, long expectedVersion) {
    CheckinTarget found =
        checkins.target(tenant, keyResultId).orElseThrow(OkrCheckinService.NotFound::new);
    CheckinTarget target =
        checkins
            .lockTarget(tenant, found.periodBucket(), found.objectiveId(), keyResultId)
            .orElseThrow(OkrCheckinService.NotFound::new);
    if (target.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, target.rowVersion());
    }
    if (!actor.equals(target.owner())) {
      throw new OkrCheckinService.Forbidden();
    }
    if ("DONG".equals(target.objectiveStatus())) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE", "Mục tiêu đã đóng, không uỷ quyền check-in được");
    }
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(target.periodId()))
            .orElseThrow(() -> new IllegalStateException("KR trỏ tới kỳ không tồn tại"));
    try {
      periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(target.ownerUnitId()));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    return target;
  }
}
