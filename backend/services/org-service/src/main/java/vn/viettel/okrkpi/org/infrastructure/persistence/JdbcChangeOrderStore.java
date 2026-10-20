package vn.viettel.okrkpi.org.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ChangeOrderRecord;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.domain.OrgConflictException;

/** Adapter {@code org.org_change_order}. */
@Repository
public class JdbcChangeOrderStore implements ChangeOrderStore {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcChangeOrderStore(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public ChangeOrderRecord insert(ChangeOrderRecord o, Actor actor) {
    try {
      OffsetDateTime appliedAt = jdbc.sql("INSERT INTO org.org_change_order (id, tenant_id, order_no, order_date, kind, "
              + "effective_on, signed_doc_ref, payload, status, applied_at, created_by, updated_by) VALUES (:id, "
              + ":tenant, :no, :date, :kind, :eff, :doc, CAST(:payload AS jsonb), :status, now(), :actor, :actor) "
              + "RETURNING applied_at")
          .param("id", o.id()).param("tenant", Sql.tenant()).param("no", o.orderNo()).param("date", o.orderDate())
          .param("kind", o.kind()).param("eff", o.effectiveOn()).param("doc", o.signedDocRef())
          .param("payload", write(o.payload())).param("status", o.status()).param("actor", actor.actorId())
          .query(OffsetDateTime.class).single();
      return new ChangeOrderRecord(o.id(), o.orderNo(), o.orderDate(), o.kind(), o.effectiveOn(), o.signedDocRef(),
          o.payload(), o.status(), appliedAt);
    } catch (DuplicateKeyException e) {
      throw new OrgConflictException("ORG-409-ORDER-NO-EXISTS", "Số quyết định đã được ghi nhận trong tenant.");
    }
  }

  @Override
  public List<UUID> predecessorsOf(UUID unitId) {
    return jdbc.sql("SELECT DISTINCT CAST(p AS uuid) FROM org.org_change_order o, "
            + "jsonb_array_elements_text(o.payload -> 'predecessorUnitIds') AS p "
            + "WHERE o.tenant_id = :tenant AND o.status = 'DA_AP_DUNG' "
            + "AND (o.payload -> 'successorUnitIds' @> jsonb_build_array(CAST(:unit AS text)) "
            + "OR o.payload -> 'createdUnitIds' @> jsonb_build_array(CAST(:unit AS text)))")
        .param("tenant", Sql.tenant()).param("unit", unitId.toString())
        .query(UUID.class).list();
  }

  @Override
  public Map<UUID, ChangeOrderRecord> findByIds(Collection<UUID> ids) {
    Map<UUID, ChangeOrderRecord> out = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT id, order_no, order_date, kind, effective_on, signed_doc_ref, payload::text AS payload, status, "
            + "applied_at FROM org.org_change_order WHERE tenant_id = :tenant AND id = ANY(CAST(:ids AS uuid[]))")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(ids))
        .query((rs, n) -> new ChangeOrderRecord(Sql.uuid(rs, "id"), rs.getString("order_no"),
            Sql.date(rs, "order_date"), rs.getString("kind"), Sql.date(rs, "effective_on"),
            rs.getString("signed_doc_ref"), read(rs.getString("payload")), rs.getString("status"),
            Sql.ts(rs, "applied_at")))
        .list().forEach(o -> out.put(o.id(), o));
    return out;
  }

  private String write(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> read(String text) {
    try {
      return json.readValue(text, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
