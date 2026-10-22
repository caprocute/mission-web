package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.RoleHolders;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link RoleHolders} đọc {@code config.role_binding} còn hiệu lực tại thời điểm hỏi. */
public class JdbcRoleHolders implements RoleHolders {

  private final JdbcClient jdbc;

  public JdbcRoleHolders(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<UUID> holders(TenantId tenantId, String roleCode, Instant at) {
    return jdbc.sql(
            """
            SELECT DISTINCT person_id FROM config.role_binding
             WHERE tenant_id = ? AND role_code = ? AND valid_from <= ? AND valid_to > ?
             ORDER BY person_id
            """)
        .param(tenantId.value())
        .param(roleCode)
        .param(Timestamp.from(at))
        .param(Timestamp.from(at))
        .query(UUID.class)
        .list();
  }
}
