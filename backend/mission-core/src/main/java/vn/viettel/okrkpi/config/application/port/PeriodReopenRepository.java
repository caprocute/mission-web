package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.PeriodReopen;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho bản chiếu mở lại hậu kiểm {@code config.period_reopen} (CORE-08-06). */
public interface PeriodReopenRepository {

  /** Thêm nếu chưa có mã yêu cầu này; {@code false} khi đã có (nhận trùng EVT-22). */
  boolean insertIfAbsent(PeriodReopen reopen, UUID actorId);

  Optional<PeriodReopen> findById(TenantId tenantId, UUID id);

  /** Lần mở lại chưa khoá lại của kỳ. */
  Optional<PeriodReopen> findOpen(TenantId tenantId, PeriodId periodId);

  /** Các lần mở lại quá hạn {@code now} chưa bắt đầu khoá lại. */
  List<PeriodReopen> dueForRelock(TenantId tenantId, Instant now, int limit);

  /** Đánh dấu bắt đầu khoá lại; {@code false} khi đã có lượt khác đánh dấu (idempotent). */
  boolean markRelockStarted(TenantId tenantId, UUID id, Instant at, UUID actorId);

  /** Đánh dấu đã khoá lại; {@code false} khi đã khoá lại từ trước. */
  boolean markRelocked(TenantId tenantId, UUID id, Instant at, UUID actorId);
}
