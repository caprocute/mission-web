package vn.viettel.okrkpi.shared.routing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hợp đồng gọi dịch vụ {@code tenant-registry} (SVC-19) để phân giải định tuyến khách thuê khi bộ
 * nhớ đệm chưa có hoặc hết hạn.
 */
public interface TenantRegistryClient {

  /** Phân giải theo mã UUID khách thuê ({@code GET /api/v1/registry/resolve?tenantId=...}). */
  Optional<TenantCellRoute> resolveByTenantId(UUID tenantId);

  /** Phân giải theo mã chữ khách thuê ({@code GET /api/v1/registry/resolve?code=...}). */
  Optional<TenantCellRoute> resolveByCode(String tenantCode);

  /** Phân giải theo tên miền tùy chỉnh ({@code GET /api/v1/registry/resolve?domain=...}). */
  default Optional<TenantCellRoute> resolveByDomain(String domain) {
    return Optional.empty();
  }

  /**
   * Nạp danh sách các khách thuê đang hoạt động để làm ấm bộ nhớ đệm lúc khởi động (mặc định trả về
   * danh sách rỗng nếu dịch vụ chỉ tra cứu theo yêu cầu).
   */
  default List<TenantCellRoute> fetchInitialRoutes() {
    return List.of();
  }
}
