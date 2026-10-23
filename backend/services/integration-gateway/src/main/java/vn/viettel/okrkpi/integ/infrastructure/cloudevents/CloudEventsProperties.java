package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.viettel.okrkpi.integ.application.outbound.JwsMode;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Cấu hình CloudEvents ra ngoài (DV-13-02, {@code okrkpi.integ.cloudevents}). Không chứa khoá hay
 * chứng thư, chỉ đường dẫn tới tệp do OpenBao Agent hoặc cert-manager gắn vào pod.
 *
 * @param cell mã cell trong {@code source}
 * @param dataSchemaBase gốc URI của {@code dataschema}
 * @param signingJwksFile tệp JWK Set chứa khoá ký của cell; trống thì không ký được (mọi lần gửi
 *     báo lỗi)
 * @param tlsDir thư mục chứa thư mục chứng thư của từng kết nối
 * @param connectTimeout thời hạn mở kết nối
 * @param requestTimeout thời hạn chờ phản hồi
 * @param connectors chính sách theo mã kết nối; mã chưa khai thì dùng {@link
 *     OutboundPolicy#defaults}
 */
@ConfigurationProperties(prefix = "okrkpi.integ.cloudevents")
public record CloudEventsProperties(
    String cell,
    String dataSchemaBase,
    Path signingJwksFile,
    Path tlsDir,
    Duration connectTimeout,
    Duration requestTimeout,
    Map<String, ConnectorPolicy> connectors) {

  public CloudEventsProperties {
    cell = cell == null || cell.isBlank() ? "cell-dev" : cell;
    dataSchemaBase =
        dataSchemaBase == null || dataSchemaBase.isBlank()
            ? "https://api.okr.viettel.vn/schemas"
            : dataSchemaBase;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    connectors = connectors == null ? Map.of() : Map.copyOf(connectors);
  }

  /** Chính sách gửi ra của một kết nối. */
  public record ConnectorPolicy(Set<Classification> allowed, JwsMode jwsMode, Boolean mtls) {}

  public OutboundPolicy policy(String connectorCode) {
    ConnectorPolicy p = connectors.get(connectorCode);
    if (p == null) {
      return OutboundPolicy.defaults(connectorCode);
    }
    return new OutboundPolicy(
        connectorCode, p.allowed(), p.jwsMode(), p.mtls() == null || p.mtls());
  }
}
