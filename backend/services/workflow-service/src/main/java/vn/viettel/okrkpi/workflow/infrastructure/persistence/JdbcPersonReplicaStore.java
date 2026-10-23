package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.PersonReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.PersonReplica;

/**
 * {@link PersonReplicaStore} trên {@code org_replica.person_v} (TL-03 "Bảng org_replica.person_v"),
 * upsert theo {@code source_version}; RLS theo {@code app.tenant_id} do inbox đặt. {@code
 * cbccvc_code} không ghi vì EVT-16 không mang trường này.
 */
@Repository
public class JdbcPersonReplicaStore implements PersonReplicaStore {

  static final String UPSERT =
      """
      INSERT INTO org_replica.person_v AS r (tenant_id, id, full_name, is_party_member,
        employment_status, source_version, source_event_id, replicated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, now())
      ON CONFLICT (tenant_id, id) DO UPDATE SET
        full_name = excluded.full_name, is_party_member = excluded.is_party_member,
        employment_status = excluded.employment_status, source_version = excluded.source_version,
        source_event_id = excluded.source_event_id, replicated_at = now()
      WHERE excluded.source_version > r.source_version
      """;

  private final JdbcTemplate jdbc;

  public JdbcPersonReplicaStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean upsert(UUID tenantId, PersonReplica p, UUID eventId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc.update(
            UPSERT,
            tenantId,
            p.id(),
            p.fullName(),
            p.partyMember(),
            p.employmentStatus(),
            p.sourceVersion(),
            eventId)
        > 0;
  }
}
