package vn.viettel.okrkpi.shared.routing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình định tuyến cell và CSDL đa khách thuê ({@code vt.platform.routing.*}).
 *
 * @param enabled bật/tắt tự động cấu hình định tuyến cell
 * @param cellCode mã cell hiện tại nơi dịch vụ đang chạy (mặc định {@code "cell-shared-01"})
 * @param registryBaseUrl địa chỉ HTTP của dịch vụ {@code tenant-registry} (SVC-19)
 * @param registryTimeout thời gian chờ tối đa khi gọi {@code tenant-registry}
 * @param cacheTtl thời gian sống của mục bộ nhớ đệm định tuyến
 * @param jdbcUrlTemplate mẫu JDBC URL tới PgBouncer của cell với biến {@code {db}} (ví dụ {@code
 *     jdbc:postgresql://pgbouncer:6432/{db}})
 * @param username tài khoản kết nối PgBouncer/PostgreSQL
 * @param password mật khẩu kết nối PgBouncer/PostgreSQL
 * @param maximumPoolSize kích thước tối đa mỗi Hikari pool của từng CSDL trên pod
 */
@ConfigurationProperties(prefix = "vt.platform.routing")
public record TenantRoutingProperties(
    boolean enabled,
    String cellCode,
    String registryBaseUrl,
    Duration registryTimeout,
    Duration cacheTtl,
    String jdbcUrlTemplate,
    String username,
    String password,
    int maximumPoolSize) {

  public TenantRoutingProperties {
    if (cellCode == null || cellCode.isBlank()) {
      cellCode = "cell-shared-01";
    }
    if (registryBaseUrl == null || registryBaseUrl.isBlank()) {
      registryBaseUrl = "http://tenant-registry:8080";
    }
    if (registryTimeout == null) {
      registryTimeout = Duration.ofSeconds(2);
    }
    if (cacheTtl == null) {
      cacheTtl = Duration.ofMinutes(5);
    }
    if (maximumPoolSize <= 0) {
      maximumPoolSize = 10;
    }
  }
}
