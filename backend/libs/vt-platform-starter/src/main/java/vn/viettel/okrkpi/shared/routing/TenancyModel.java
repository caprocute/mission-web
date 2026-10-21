package vn.viettel.okrkpi.shared.routing;

import java.util.Locale;

/**
 * Mô hình cô lập dữ liệu khách thuê theo ADR-07 và TL-03 §2.1.
 *
 * <ul>
 *   <li>{@link #POOL}: dùng chung CSDL {@code td_pool}, tách bằng RLS {@code app.tenant_id}.
 *   <li>{@link #BRIDGE}: mỗi khách thuê một CSDL {@code td_<code_lower>} trên cụm PG chung của
 *       cell.
 *   <li>{@link #SILO}: mỗi khách thuê một CSDL {@code td_<code_lower>} trên cụm PG chuyên biệt.
 *   <li>{@link #AIRGAP}: triển khai tách biệt mạng (on-prem / miền mật).
 * </ul>
 */
public enum TenancyModel {
  POOL,
  BRIDGE,
  SILO,
  AIRGAP;

  /**
   * Phân tích chuỗi mô hình từ JSON hoặc Avro (hỗ trợ cả chữ hoa lẫn chữ thường).
   *
   * @param raw giá trị chuỗi (ví dụ {@code "BRIDGE"}, {@code "bridge"}, {@code "POOL"})
   * @return {@link TenancyModel} tương ứng; mặc định {@link #POOL} nếu rỗng
   */
  public static TenancyModel fromString(String raw) {
    if (raw == null || raw.isBlank()) {
      return POOL;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "POOL" -> POOL;
      case "BRIDGE" -> BRIDGE;
      case "SILO" -> SILO;
      case "AIRGAP" -> AIRGAP;
      default -> throw new IllegalArgumentException("Unknown tenancyModel: " + raw);
    };
  }

  /**
   * Suy diễn tên CSDL chuẩn theo quy ước ADR-07 nếu {@code databaseName} không được chỉ định tường
   * minh.
   */
  public String defaultDatabaseName(String tenantCode) {
    if (this == POOL) {
      return "td_pool";
    }
    if (tenantCode == null || tenantCode.isBlank()) {
      throw new IllegalArgumentException("tenantCode must not be blank for model " + this);
    }
    String sanitized = tenantCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    return "td_" + sanitized;
  }
}
