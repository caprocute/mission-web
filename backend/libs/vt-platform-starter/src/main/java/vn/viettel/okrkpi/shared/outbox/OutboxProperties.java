package vn.viettel.okrkpi.shared.outbox;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình outbox ({@code okrkpi.outbox.*}). Khai {@code schema} là bật {@link OutboxWriter}.
 *
 * @param schema schema của mô-đun gọi, nơi có bảng {@code outbox_event} (ví dụ {@code task})
 * @param registry kết nối Apicurio Registry
 */
@ConfigurationProperties(prefix = "okrkpi.outbox")
public record OutboxProperties(String schema, Registry registry) {

  public OutboxProperties {
    registry = registry == null ? new Registry(null, null) : registry;
  }

  /**
   * @param url địa chỉ API v3, ví dụ {@code http://apicurio:8080/apis/registry/v3}; thông tin kết
   *     nối nằm ở cấu hình môi trường, không nằm trong mã (YC-PT-13)
   * @param properties cấu hình serde bổ sung, khoá đầy đủ {@code apicurio.registry.*} (xác thực, số
   *     lần thử lại…); bí mật lấy từ biến môi trường hoặc OpenBao, không ghi vào tệp cấu hình
   */
  public record Registry(String url, Map<String, String> properties) {

    public Registry {
      properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
  }
}
