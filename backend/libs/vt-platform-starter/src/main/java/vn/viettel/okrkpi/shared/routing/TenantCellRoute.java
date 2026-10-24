package vn.viettel.okrkpi.shared.routing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bản ghi định tuyến CSDL và cell của một khách thuê trong bộ nhớ ({@code registry.tenant_cell},
 * ADR-07).
 *
 * @param tenantId mã UUID khách thuê (có thể {@code null} nếu nhận từ sự kiện chỉ chứa {@code
 *     tenantCode} và chưa ánh xạ UUID)
 * @param tenantCode mã chữ khách thuê (ví dụ {@code "hcm_a"}, {@code "bo_x"})
 * @param cellCode mã cell đích (ví dụ {@code "cell-hcm-01"}, {@code "cell-shared-01"})
 * @param cellApiBaseUrl địa chỉ gốc API của cell đích (tùy chọn)
 * @param tenancyModel mô hình cô lập ({@link TenancyModel#POOL}, {@link TenancyModel#BRIDGE},
 *     {@link TenancyModel#SILO}, {@link TenancyModel#AIRGAP})
 * @param databaseName tên CSDL PostgreSQL trên PgBouncer của cell (ví dụ {@code "td_pool"}, {@code
 *     "td_hcm_a"})
 * @param status trạng thái vòng đời khách thuê ({@code DANG_VAN_HANH}, {@code SAN_SANG}, {@code
 *     DANG_CHUYEN_CELL}, {@code TAM_NGUNG}, {@code DA_CHAM_DUT}...)
 * @param domains danh sách tên miền tùy chỉnh của khách thuê
 * @param sourceVersion phiên bản bản ghi bên {@code tenant-registry} để chống ghi đè lùi
 * @param expiresAt thời điểm hết hạn mục bộ nhớ đệm cục bộ
 */
public record TenantCellRoute(
    UUID tenantId,
    String tenantCode,
    String cellCode,
    String cellApiBaseUrl,
    TenancyModel tenancyModel,
    String databaseName,
    String status,
    List<String> domains,
    long sourceVersion,
    Instant expiresAt) {

  public TenantCellRoute {
    Objects.requireNonNull(tenantCode, "tenantCode must not be null");
    Objects.requireNonNull(cellCode, "cellCode must not be null");
    Objects.requireNonNull(tenancyModel, "tenancyModel must not be null");
    if (databaseName == null || databaseName.isBlank()) {
      databaseName = tenancyModel.defaultDatabaseName(tenantCode);
    }
    if (status == null || status.isBlank()) {
      status = "DANG_VAN_HANH";
    }
    domains = domains == null ? List.of() : List.copyOf(domains);
    if (expiresAt == null) {
      expiresAt = Instant.MAX;
    }
  }

  /** Kiểm tra khách thuê đang trong quá trình di chuyển sang cell khác ({@code 503}). */
  public boolean isMigratingCell() {
    return "DANG_CHUYEN_CELL".equalsIgnoreCase(status);
  }

  /**
   * Kiểm tra khách thuê đang chuyển cell hoặc đã chấm dứt ({@code 503}). Khách thuê {@code
   * TAM_NGUNG} không thuộc nhóm này: TL-04 mục 11.4 giữ dữ liệu và chỉ chặn ghi, xem {@link
   * #isSuspended()}.
   */
  public boolean isUnavailableStatus() {
    return "DANG_CHUYEN_CELL".equalsIgnoreCase(status) || "DA_CHAM_DUT".equalsIgnoreCase(status);
  }

  /**
   * Kiểm tra khách thuê đang tạm ngừng ({@code TAM_NGUNG}): vẫn đọc được dữ liệu, lệnh ghi của
   * người dùng bị từ chối ({@code 403}, TL-04 mục 11.4).
   */
  public boolean isSuspended() {
    return "TAM_NGUNG".equalsIgnoreCase(status);
  }

  /** Kiểm tra mục bộ nhớ đệm đã hết hạn tại thời điểm {@code now} hay chưa. */
  public boolean isExpiredAt(Instant now) {
    return now.isAfter(expiresAt);
  }
}
