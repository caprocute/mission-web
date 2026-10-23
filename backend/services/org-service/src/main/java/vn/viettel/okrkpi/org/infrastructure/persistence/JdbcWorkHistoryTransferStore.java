package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.port.WorkHistoryTransferStore;

/**
 * Adapter DV-13-10: lý lịch công tác từ {@code org.assignment} (phiên bản đang ghi nhận, đơn vị và chức danh có hiệu
 * lực tại ngày bắt đầu phân công) và phần nhận về {@code org.transferred_work_history}.
 */
@Repository
public class JdbcWorkHistoryTransferStore implements WorkHistoryTransferStore {

  private final JdbcClient jdbc;

  public JdbcWorkHistoryTransferStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Entry> history(UUID personId) {
    return jdbc.sql("SELECT u.code AS unit_code, u.name AS unit_name, j.code AS title_code, j.name AS title_name, "
            + "a.kind, a.branch, a.is_head, a.valid_from, "
            + "CASE WHEN a.valid_to = 'infinity' THEN NULL ELSE a.valid_to END AS valid_to, "
            + "co.order_no, co.signed_doc_ref FROM org.assignment a "
            + "LEFT JOIN LATERAL (SELECT x.code, x.name FROM org.org_unit x WHERE x.tenant_id = a.tenant_id "
            + "AND x.id = a.org_unit_id AND x.recorded_to = 'infinity' AND x.valid_from <= a.valid_from "
            + "ORDER BY x.valid_from DESC LIMIT 1) u ON true "
            + "LEFT JOIN LATERAL (SELECT t.code, t.name FROM org.job_title t WHERE t.tenant_id = a.tenant_id "
            + "AND t.id = a.job_title_id ORDER BY t.valid_from DESC LIMIT 1) j ON true "
            + "LEFT JOIN org.org_change_order co ON co.tenant_id = a.tenant_id AND co.id = a.change_order_id "
            + "WHERE a.tenant_id = :tenant AND a.person_id = :person AND a.recorded_to = 'infinity' "
            + "ORDER BY a.valid_from, a.branch, a.kind, a.id")
        .param("tenant", Sql.tenant()).param("person", personId)
        .query((rs, i) -> {
          Date to = rs.getDate("valid_to");
          return new Entry(rs.getString("unit_code"), rs.getString("unit_name"), rs.getString("title_code"),
              rs.getString("title_name"), rs.getString("kind"), rs.getString("branch"), rs.getBoolean("is_head"),
              rs.getDate("valid_from").toLocalDate(), to == null ? null : to.toLocalDate(), rs.getString("order_no"),
              rs.getString("signed_doc_ref"));
        }).list();
  }

  @Override
  public Optional<byte[]> importedSha256(UUID packageId, String partName) {
    return jdbc.sql("SELECT content_sha256 FROM org.transferred_work_history WHERE tenant_id = :tenant "
            + "AND package_id = :package AND part_name = :part")
        .param("tenant", Sql.tenant()).param("package", packageId).param("part", partName)
        .query((rs, i) -> rs.getBytes(1)).optional();
  }

  @Override
  public boolean insertImported(Imported row, UUID actor) {
    return jdbc.sql("INSERT INTO org.transferred_work_history (id, tenant_id, package_id, part_name, "
            + "source_tenant_code, source_person_id, content, content_sha256, created_by, updated_by) VALUES "
            + "(:id, :tenant, :package, :part, :source, :person, CAST(:content AS jsonb), :sha, :actor, :actor) "
            + "ON CONFLICT DO NOTHING")
        .param("id", row.id()).param("tenant", Sql.tenant()).param("package", row.packageId())
        .param("part", row.partName()).param("source", row.sourceTenantCode()).param("person", row.sourcePersonId())
        .param("content", row.content()).param("sha", row.contentSha256()).param("actor", actor)
        .update() == 1;
  }
}
