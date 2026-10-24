package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu {@code config.period_lock_order}, đọc {@code config.period_lock_step}. */
public interface LockOrderRepository {

  /** Kỳ đã có lệnh chưa huỷ ({@code ix_period_lock_order__period_active}). */
  final class ActiveOrderExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ActiveOrderExistsException(Throwable cause) {
      super("Kỳ đã có lệnh khoá chưa huỷ", cause);
    }
  }

  void insert(PeriodLockOrder order, UUID actorId);

  /**
   * Ghi trạng thái, người duyệt, người khởi chạy, saga, cờ tạm dừng và đề nghị huỷ khi {@code
   * row_version} khớp.
   */
  boolean update(PeriodLockOrder order, int expectedVersion, UUID actorId);

  Optional<PeriodLockOrder> findById(TenantId tenantId, UUID id);

  /** Khoá dòng để tuần tự hoá khởi chạy (SELECT … FOR UPDATE). */
  Optional<PeriodLockOrder> lockById(TenantId tenantId, UUID id);

  /** Lệnh chưa huỷ của kỳ. */
  Optional<PeriodLockOrder> active(TenantId tenantId, PeriodId periodId);

  /** Lệnh mới nhất của kỳ, kể cả đã huỷ. */
  Optional<PeriodLockOrder> latest(TenantId tenantId, PeriodId periodId);

  /** Số lệnh có khoá chống lặp bắt đầu bằng {@code keyPrefix}. */
  int countKeys(TenantId tenantId, String keyPrefix);

  /** Các bước đã chiếu của lệnh, theo số bước tăng dần. */
  List<LockStep> steps(TenantId tenantId, UUID lockOrderId);

  /** Lệnh đã khởi chạy saga {@code sagaId}. */
  Optional<PeriodLockOrder> findBySaga(TenantId tenantId, UUID sagaId);

  /** Ghi bản chiếu một bước ({@code step_no = stepNo + 1}), thay dòng cũ của cùng bước. */
  void upsertStep(TenantId tenantId, UUID lockOrderId, LockStep step);
}
