package vn.viettel.okrkpi.integ.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Cấu hình một kết nối hệ ngoài của tenant ({@code integ.connector}); bí mật nằm ở OpenBao, ở đây
 * chỉ có đường dẫn.
 */
public record Connector(
    UUID id,
    UUID tenantId,
    String code,
    Protocol protocol,
    String endpoint,
    String secretPath,
    Direction direction,
    String scheduleCron,
    boolean enabled) {

  public Connector {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(protocol, "protocol");
    Objects.requireNonNull(direction, "direction");
  }
}
