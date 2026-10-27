package vn.viettel.okrkpi.config.application.port;

import java.util.Optional;

/**
 * Danh mục quyền sinh từ ma trận phân quyền ({@code docs/phan-quyen/gen_matrix.py --rego}): quyền
 * của từng vai trò hệ thống trên từng chức năng, dùng để kiểm vai trò tuỳ chỉnh chỉ thu hẹp vai trò
 * gốc (BR-01-23).
 */
public interface PermissionCatalogPort {

  /**
   * Phạm vi (theo cách viết của hợp đồng) mà {@code role} có trên chức năng {@code functionKey}.
   */
  Optional<String> scopeOf(String functionKey, String role);

  /** Phiên bản ma trận. */
  String version();
}
