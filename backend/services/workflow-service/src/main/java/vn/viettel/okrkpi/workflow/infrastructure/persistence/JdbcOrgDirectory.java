package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;

/**
 * {@link OrgDirectory} trên bản sao {@code org_replica.org_unit_v} của workflow-service (TL-03
 * "Schema org_replica"). Truy vấn chạm khoá chính, chạy trong giao dịch khởi tạo tiến trình.
 */
@Repository
public class JdbcOrgDirectory implements OrgDirectory {

  private final JdbcTemplate jdbc;

  public JdbcOrgDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<OrgUnit> unit(UUID tenantId, UUID unitId, LocalDate on) {
    TenantBinding.bind(jdbc, tenantId);
    List<OrgUnit> rows =
        jdbc.query(
            "SELECT id, unit_type, party_parent_id, admin_parent_id FROM org_replica.org_unit_v"
                + " WHERE tenant_id = ? AND id = ? AND valid_from <= ? AND valid_to > ?"
                + " ORDER BY valid_from DESC LIMIT 1",
            (rs, i) ->
                new OrgUnit(
                    rs.getObject("id", UUID.class),
                    rs.getString("unit_type"),
                    rs.getObject("party_parent_id", UUID.class),
                    rs.getObject("admin_parent_id", UUID.class)),
            tenantId,
            unitId,
            Date.valueOf(on),
            Date.valueOf(on));
    return rows.stream().findFirst();
  }
}
