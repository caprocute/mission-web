package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.AssigneeDirectory;

/**
 * {@link AssigneeDirectory} trên bản sao {@code org_replica.assignment_v} (trục hiệu lực {@code
 * [valid_from, valid_to)}) và {@code org_replica.person_v} (tình trạng công tác) của TL-03, RLS
 * theo tenant.
 */
@Repository
public class JdbcAssigneeDirectory implements AssigneeDirectory {

  private final JdbcTemplate jdbc;

  public JdbcAssigneeDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean activeIn(UUID tenantId, UUID personId, UUID unitId, LocalDate on) {
    TenantBinding.bind(jdbc, tenantId);
    String sql =
        "SELECT EXISTS (SELECT 1 FROM org_replica.assignment_v WHERE tenant_id = ?"
            + " AND person_id = ? AND valid_from <= ? AND valid_to > ?"
            + (unitId == null ? "" : " AND org_unit_id = ?")
            + ")";
    Date day = Date.valueOf(on);
    Boolean found =
        unitId == null
            ? jdbc.queryForObject(sql, Boolean.class, tenantId, personId, day, day)
            : jdbc.queryForObject(sql, Boolean.class, tenantId, personId, day, day, unitId);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public Optional<String> employmentStatus(UUID tenantId, UUID personId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc
        .queryForList(
            "SELECT employment_status FROM org_replica.person_v WHERE tenant_id = ? AND id = ?",
            String.class,
            tenantId,
            personId)
        .stream()
        .findFirst();
  }
}
