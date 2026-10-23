package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.RoleHolderDirectory;

/**
 * {@link RoleHolderDirectory} trên bản sao {@code org_replica.role_holder_v} (V7, RLS theo tenant).
 */
@Repository
public class JdbcRoleHolderDirectory implements RoleHolderDirectory {

  private final JdbcTemplate jdbc;

  public JdbcRoleHolderDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, List<UUID>> holders(
      UUID tenantId, String role, List<UUID> units, java.time.LocalDate on) {
    Map<UUID, List<UUID>> out = new LinkedHashMap<>();
    if (units.isEmpty()) {
      return out;
    }
    TenantBinding.bind(jdbc, tenantId);
    String marks = String.join(",", java.util.Collections.nCopies(units.size(), "?"));
    List<Object> args =
        new ArrayList<>(List.of(tenantId, role, Date.valueOf(on), Date.valueOf(on)));
    args.addAll(units);
    jdbc.query(
        "SELECT org_unit_id, user_id FROM org_replica.role_holder_v"
            + " WHERE tenant_id = ? AND role_code = ? AND valid_from <= ? AND valid_to > ?"
            + " AND org_unit_id IN ("
            + marks
            + ") ORDER BY org_unit_id, user_id",
        rs -> {
          out.computeIfAbsent(rs.getObject(1, UUID.class), u -> new ArrayList<>())
              .add(rs.getObject(2, UUID.class));
        },
        args.toArray());
    return out;
  }
}
