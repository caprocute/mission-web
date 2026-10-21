package vn.viettel.okrkpi.org.infrastructure.republish;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình lệnh {@code org-republish}; không chứa mật khẩu (thông tin xác thực Kafka, registry nạp từ biến môi
 * trường do External Secrets đồng bộ).
 *
 * @param bootstrapServers địa chỉ Kafka
 * @param registryUrl địa chỉ API v3 của Apicurio Registry
 * @param kafka cấu hình producer bổ sung (khoá Kafka đầy đủ, ví dụ {@code security.protocol})
 * @param registry cấu hình serde bổ sung (khoá {@code apicurio.registry.*})
 * @param exitOnFinish thoát tiến trình khi chạy xong (tắt trong kiểm thử)
 */
@ConfigurationProperties("okrkpi.org.republish")
public record RepublishProperties(
    String bootstrapServers, String registryUrl, Map<String, String> kafka, Map<String, String> registry,
    Boolean exitOnFinish) {

  public RepublishProperties {
    kafka = kafka == null ? Map.of() : Map.copyOf(kafka);
    registry = registry == null ? Map.of() : Map.copyOf(registry);
    exitOnFinish = exitOnFinish == null || exitOnFinish;
  }
}
