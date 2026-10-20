package vn.viettel.okrkpi.shared.error;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình bộ xử lý lỗi ({@code okrkpi.problem.*}).
 *
 * @param module phần MODULE mặc định cho mã {@code <MODULE>-409-VERSION-CONFLICT} khi không suy ra
 *     được từ gói của controller (microservice đặt, ví dụ {@code REGISTRY}); bỏ trống thì dùng
 *     {@code COMMON}
 */
@ConfigurationProperties(prefix = "okrkpi.problem")
public record ProblemProperties(String module) {}
