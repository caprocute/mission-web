package vn.viettel.okrkpi.integ.infrastructure.cbccvc;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.rating.SubjectChains;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.outbound_subject}. */
public class JdbcSubjectChains implements SubjectChains {

  private final JdbcClient jdbc;

  public JdbcSubjectChains(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Subject> lock(TenantId tenant, UUID connectorId, String subjectKey) {
    return jdbc.sql(
            "SELECT id, current_event_id, previous_event_id, aggregate_version"
                + " FROM integ.outbound_subject WHERE tenant_id = ? AND connector_id = ?"
                + " AND subject_key = ? FOR UPDATE")
        .params(tenant.value(), connectorId, subjectKey)
        .query(
            (rs, i) ->
                new Subject(
                    rs.getObject("id", UUID.class),
                    rs.getObject("current_event_id", UUID.class),
                    rs.getObject("previous_event_id", UUID.class),
                    rs.getInt("aggregate_version")))
        .optional();
  }

  @Override
  public void insert(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      String subjectKey,
      UUID eventId,
      int aggregateVersion) {
    jdbc.sql(
            "INSERT INTO integ.outbound_subject (id, tenant_id, connector_id, subject_key,"
                + " current_event_id, aggregate_version) VALUES (?, ?, ?, ?, ?, ?)")
        .params(id, tenant.value(), connectorId, subjectKey, eventId, aggregateVersion)
        .update();
  }

  @Override
  public void advance(
      TenantId tenant, UUID id, UUID eventId, UUID previousEventId, int aggregateVersion) {
    jdbc.sql(
            "UPDATE integ.outbound_subject SET current_event_id = ?, previous_event_id = ?,"
                + " aggregate_version = ?, updated_at = now() WHERE tenant_id = ? AND id = ?")
        .params(eventId, previousEventId, aggregateVersion, tenant.value(), id)
        .update();
  }
}
