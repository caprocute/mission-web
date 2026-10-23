package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Date;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.AssignmentReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.AssignmentReplica;

/**
 * {@link AssignmentReplicaStore} trên {@code org_replica.assignment_v} (TL-03 "Bảng
 * org_replica.assignment_v", mẫu upsert {@code WHERE r.source_version < EXCLUDED.source_version}).
 * Ngày hết hiệu lực rỗng ghi {@code 'infinity'}; RLS theo {@code app.tenant_id} do inbox đặt.
 */
@Repository
public class JdbcAssignmentReplicaStore implements AssignmentReplicaStore {

  static final String UPSERT =
      """
      INSERT INTO org_replica.assignment_v AS r (tenant_id, id, valid_from, valid_to, person_id,
        org_unit_id, position_id, job_title_id, kind, branch, is_head, source_version,
        source_event_id, replicated_at)
      VALUES (?, ?, ?, coalesce(cast(? AS date), 'infinity'), ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
      ON CONFLICT (tenant_id, id, valid_from) DO UPDATE SET
        valid_to = excluded.valid_to, person_id = excluded.person_id,
        org_unit_id = excluded.org_unit_id, position_id = excluded.position_id,
        job_title_id = excluded.job_title_id, kind = excluded.kind, branch = excluded.branch,
        is_head = excluded.is_head, source_version = excluded.source_version,
        source_event_id = excluded.source_event_id, replicated_at = now()
      WHERE excluded.source_version > r.source_version
      """;

  private final JdbcTemplate jdbc;

  public JdbcAssignmentReplicaStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean upsert(UUID tenantId, AssignmentReplica a, UUID eventId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc.update(
            UPSERT,
            tenantId,
            a.id(),
            Date.valueOf(a.validFrom()),
            a.validTo() == null ? null : Date.valueOf(a.validTo()),
            a.personId(),
            a.orgUnitId(),
            a.positionId(),
            a.jobTitleId(),
            a.kind(),
            a.branch(),
            a.head(),
            a.sourceVersion(),
            eventId)
        > 0;
  }
}
