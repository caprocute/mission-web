package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Date;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.OrgUnitReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTree;
import vn.viettel.okrkpi.workflow.domain.replica.OrgUnitReplica;

/**
 * {@link OrgUnitReplicaStore} trên {@code org_replica.org_unit_v} (TL-03 "Bảng
 * org_replica.org_unit_v", mẫu upsert {@code WHERE excluded.source_version > source_version}). Ngày
 * hết hiệu lực rỗng ghi {@code 'infinity'}; RLS theo {@code app.tenant_id} do inbox đặt.
 */
@Repository
public class JdbcOrgUnitReplicaStore implements OrgUnitReplicaStore {

  static final String UPSERT =
      """
      INSERT INTO org_replica.org_unit_v AS r (tenant_id, id, valid_from, valid_to, code, name,
        unit_type, party_parent_id, admin_parent_id, subject_group_id, source_version,
        source_event_id, replicated_at)
      VALUES (?, ?, ?, coalesce(cast(? AS date), 'infinity'), ?, ?, ?, ?, ?, ?, ?, ?, now())
      ON CONFLICT (tenant_id, id, valid_from) DO UPDATE SET
        valid_to = excluded.valid_to, code = excluded.code, name = excluded.name,
        unit_type = excluded.unit_type, party_parent_id = excluded.party_parent_id,
        admin_parent_id = excluded.admin_parent_id, subject_group_id = excluded.subject_group_id,
        source_version = excluded.source_version, source_event_id = excluded.source_event_id,
        replicated_at = now()
      WHERE excluded.source_version > r.source_version
      """;

  /**
   * Đi lên theo cấp trên của phiên bản mở; {@code UNION} khử trùng nên dừng cả khi dữ liệu đã có
   * vòng. Tên cột cấp trên là hằng theo cây, không lấy từ đầu vào.
   */
  private static final String REACHES =
      """
      WITH RECURSIVE up(id) AS (
        SELECT cast(? AS uuid)
        UNION
        SELECT u.%1$s FROM org_replica.org_unit_v u JOIN up ON u.id = up.id
         WHERE u.tenant_id = ? AND u.valid_to = 'infinity' AND u.%1$s IS NOT NULL)
      SELECT EXISTS (SELECT 1 FROM up WHERE id = ?)
      """;

  static final String REACHES_DANG = REACHES.formatted("party_parent_id");
  static final String REACHES_CHUYEN_MON = REACHES.formatted("admin_parent_id");

  private final JdbcTemplate jdbc;

  public JdbcOrgUnitReplicaStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean upsert(UUID tenantId, OrgUnitReplica u, UUID eventId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc.update(
            UPSERT,
            tenantId,
            u.id(),
            Date.valueOf(u.validFrom()),
            u.validTo() == null ? null : Date.valueOf(u.validTo()),
            u.code(),
            u.name(),
            u.unitType(),
            u.partyParentId(),
            u.adminParentId(),
            u.subjectGroupId(),
            u.sourceVersion(),
            eventId)
        > 0;
  }

  @Override
  public boolean reachesUpward(UUID tenantId, OrgTree tree, UUID from, UUID target) {
    TenantBinding.bind(jdbc, tenantId);
    String sql = tree == OrgTree.DANG ? REACHES_DANG : REACHES_CHUYEN_MON;
    return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, from, tenantId, target));
  }
}
