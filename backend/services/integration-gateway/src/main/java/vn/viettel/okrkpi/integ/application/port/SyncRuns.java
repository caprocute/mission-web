package vn.viettel.okrkpi.integ.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng bảng {@code integ.sync_run}: lần đồng bộ kéo hết trang của một kết nối (DV-13-06). */
public interface SyncRuns {

  /** Kiểu lần chạy. */
  enum Kind {
    LICH,
    NGAY,
    CHAY_LAI
  }

  /** Trạng thái lần chạy. */
  enum Status {
    CHO,
    DANG_CHAY,
    XONG,
    LOI;

    public boolean open() {
      return this == CHO || this == DANG_CHAY;
    }
  }

  record Run(
      UUID id,
      UUID connectorId,
      Kind kind,
      Status status,
      UUID retryOf,
      int pages,
      int messages,
      int duplicates,
      int deadLetters,
      String errorDetail,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt) {}

  /** Lần chạy cần worker nhận ở mọi tenant (hàm {@code fn_lan_dong_bo_can_chay}). */
  record Due(UUID tenantId, UUID id) {}

  /** Thêm lần chạy {@code CHO}; trả false khi kết nối đã có lần chạy đang mở. */
  boolean insert(TenantId tenant, UUID id, UUID connectorId, Kind kind, UUID retryOf, Instant at);

  Optional<Run> find(TenantId tenant, UUID id);

  /** Lần chạy đang mở của kết nối. */
  Optional<Run> open(TenantId tenant, UUID connectorId);

  /** Lần yêu cầu gần nhất theo kiểu của kết nối. */
  Optional<Instant> lastRequested(TenantId tenant, UUID connectorId, Kind kind);

  List<Due> due(int limit, int leaseSeconds);

  /** Nhận lần chạy: {@code CHO} hoặc {@code DANG_CHAY} đã mất nhịp quá hạn thuê. */
  boolean claim(TenantId tenant, UUID id, int leaseSeconds, Instant at);

  void progress(TenantId tenant, UUID id, int pages, int messages, int duplicates, Instant at);

  void finish(TenantId tenant, UUID id, Status status, int deadLetters, String error, Instant at);
}
