package vn.viettel.okrkpi.shared.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình quan sát ({@code okrkpi.observability.*}).
 *
 * @param cell tên cell, gắn làm thẻ {@code cell} của mọi metric (cùng giá trị thuộc tính tài nguyên
 *     OpenTelemetry {@code cell}); mặc định lấy biến môi trường {@code OKRKPI_CELL}
 */
@ConfigurationProperties(prefix = "okrkpi.observability")
public record ObservabilityProperties(String cell) {}
