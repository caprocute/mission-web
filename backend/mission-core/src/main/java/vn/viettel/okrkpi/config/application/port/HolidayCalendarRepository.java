package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.HolidayDay;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu {@code config.holiday_calendar}; gọi trong giao dịch {@code configTransactionManager}. */
public interface HolidayCalendarRepository {

  /** Ngày đã có trong lịch của tenant ({@code uq_holiday_calendar__date}). */
  final class DateExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DateExistsException(Throwable cause) {
      super("Ngày đã có trong lịch", cause);
    }
  }

  void insert(TenantId tenantId, HolidayDay day, UUID actorId);

  /** Sửa khi {@code row_version} khớp; trả {@code false} nếu lệch hoặc không có dòng. */
  boolean update(TenantId tenantId, HolidayDay day, int expectedVersion, UUID actorId);

  /** Xoá khi {@code row_version} khớp; trả {@code false} nếu lệch hoặc không có dòng. */
  boolean delete(TenantId tenantId, UUID id, int expectedVersion);

  Optional<HolidayDay> findById(TenantId tenantId, UUID id);

  /** Mọi dòng của tenant, theo ngày tăng dần (mỗi tenant chỉ vài chục dòng mỗi năm). */
  List<HolidayDay> all(TenantId tenantId);
}
