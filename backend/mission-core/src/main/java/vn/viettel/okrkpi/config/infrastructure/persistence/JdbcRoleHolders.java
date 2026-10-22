package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.RoleHolders;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link RoleHolders} đọc {@code config.role_binding} còn hiệu lực tại thời điểm hỏi: chưa kết
 * thúc, và với vai trò nhạy cảm thì đã qua duyệt thứ hai (BR-01-14, CORE-08-16).
 */
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
               AND end_kind IS NULL
               AND (approved_by IS NOT NULL
                    OR role_code NOT IN ('decider', 'second_approver', 'policy_author'))
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
