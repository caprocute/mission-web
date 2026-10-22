package vn.viettel.okrkpi.config.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho kỳ đánh giá ({@code config.period}, {@code config.period_status_log}). */
public interface PeriodRepository {

  /**
   * Ghi kỳ mới.
   *
   * @throws PeriodConflictException khi CSDL từ chối vì chồng lấn hoặc trùng mã
   */
  void insert(Period period);

  /**
   * Ghi trạng thái mới của kỳ nếu {@code row_version} vẫn là {@code expectedVersion}.
   *
   * @return {@code false} khi phiên bản đã đổi (người khác vừa ghi)
   */
  boolean updateState(Period period, int expectedVersion, UUID actorId);

  /** Ghi một dòng nhật ký chuyển trạng thái (chỉ ghi thêm). */
  void appendStatusLog(TenantId tenantId, PeriodTransition transition);

  Optional<Period> findById(TenantId tenantId, PeriodId periodId);

  boolean existsById(TenantId tenantId, PeriodId periodId);

  /** Có kỳ cùng loại, cùng luồng giao với khoảng ngày (khoảng đóng). */
  boolean overlaps(
      TenantId tenantId, PeriodKind kind, PeriodFlow flow, LocalDate start, LocalDate end);

  /** Các kỳ chứa ngày {@code day}; {@code kind} trống là mọi loại. */
  List<Period> covering(TenantId tenantId, PeriodKind kind, LocalDate day);

  /** Các kỳ có ngày bắt đầu trong {@code [from, to]}. */
  List<Period> startingBetween(TenantId tenantId, LocalDate from, LocalDate to);

  /**
   * Một trang kỳ theo thứ tự ngày bắt đầu giảm dần rồi {@code id} giảm dần, sau keyset {@code
   * after} (trống là trang đầu).
   */
  List<Period> list(TenantId tenantId, Filter filter, Keyset after, int fetchSize);

  /**
   * Đánh dấu đã phát sự kiện mở kỳ, chạy idempotent giữa nhiều pod.
   *
   * @return {@code true} khi lần gọi này là lần đánh dấu đầu tiên
   */
  boolean markOpeningAnnounced(TenantId tenantId, PeriodId periodId);

  /** Bộ lọc của {@code listPeriods}; trường trống là không lọc. */
  record Filter(Integer year, PeriodKind kind, PeriodFlow flow, PeriodState state) {}

  /** Vị trí keyset của trang trước. */
  record Keyset(LocalDate startDate, UUID id) {}
}
