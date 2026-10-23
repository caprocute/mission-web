package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.document.IncomingDocService;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs;

/**
 * {@link IncomingDocs} trên {@code task.incoming_doc}, {@code task.incoming_doc_claim} và {@code
 * task.task_source_doc} (CORE-02-16), vai trò {@code svc_task} dưới RLS.
 */
public class JdbcIncomingDocs implements IncomingDocs {

  private static final TypeReference<List<Paragraph>> PARAGRAPHS = new TypeReference<>() {};

  private static final String COLS =
      "id, recipient_unit_id, source_system, connector_code, external_doc_id, doc_number,"
          + " doc_date, issuer, excerpt, doc_format, issuer_header, title, preamble,"
          + " paragraphs::text AS paragraphs, recipients, classification, content_loaded,"
          + " doc_version, received_event_id, received_at, status, dismiss_reason, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcIncomingDocs(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void upsertReceived(TenantId tenant, Received r) {
    UUID actor = IncomingDocService.systemActor();
    String excerpt = cut(r.excerpt(), 1000);
    jdbc.sql(
            "INSERT INTO task.incoming_doc (id, tenant_id, created_by, updated_by, source_system,"
                + " connector_code, external_doc_id, doc_number, doc_date, issuer, excerpt,"
                + " paragraphs, object_key, doc_version, received_event_id, received_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, id) DO UPDATE SET doc_number = EXCLUDED.doc_number,"
                + " doc_date = EXCLUDED.doc_date, issuer = EXCLUDED.issuer,"
                + " excerpt = EXCLUDED.excerpt, object_key = EXCLUDED.object_key,"
                + " doc_version = EXCLUDED.doc_version,"
                + " received_event_id = EXCLUDED.received_event_id, updated_at = now(),"
                + " row_version = task.incoming_doc.row_version + 1"
                + " WHERE task.incoming_doc.doc_version < EXCLUDED.doc_version")
        .params(
            r.id(),
            tenant.value(),
            actor,
            actor,
            r.sourceSystem(),
            cut(r.connectorCode(), 64),
            cut(r.externalId(), 128),
            cut(r.docNumber(), 64),
            r.docDate(),
            cut(r.issuer(), 500),
            excerpt,
            write(List.of(new Paragraph(1, excerpt))),
            cut(r.objectKey(), 512),
            r.docVersion(),
            r.eventId(),
            Timestamp.from(r.receivedAt()))
        .update();
  }

  @Override
  public void storeContent(TenantId tenant, UUID id, Content c) {
    List<Paragraph> paragraphs = new ArrayList<>();
    for (Paragraph p : c.paragraphs()) {
      if (p != null && p.text() != null && !p.text().isBlank() && p.no() >= 1) {
        paragraphs.add(p);
      }
    }
    jdbc.sql(
            "UPDATE task.incoming_doc SET recipient_unit_id = ?, doc_format = coalesce(?,"
                + " doc_format), issuer_header = ?, title = ?, preamble = ?,"
                + " paragraphs = CASE WHEN ? THEN paragraphs ELSE ?::jsonb END, recipients = ?,"
                + " classification = ?, content_loaded = true, updated_at = now(),"
                + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?")
        .params(
            c.recipientUnitId(),
            cut(c.format(), 16),
            cut(c.issuerHeader(), 500),
            cut(c.title(), 1000),
            cut(c.preamble(), 4000),
            paragraphs.isEmpty(),
            write(paragraphs),
            cut(c.recipients(), 2000),
            c.classification() == null ? "C1" : c.classification(),
            tenant.value(),
            id)
        .update();
  }

  @Override
  public Optional<DocRow> find(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM task.incoming_doc WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query((rs, i) -> row(rs))
        .optional();
  }

  @Override
  public List<DocRow> list(TenantId tenant, UUID unitId, String status, int limit, After after) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLS)
            .append(" FROM task.incoming_doc WHERE tenant_id = ? AND recipient_unit_id = ?");
    List<Object> params = new ArrayList<>(List.of(tenant.value(), unitId));
    if (status != null) {
      sql.append(" AND status = ?");
      params.add(status);
    }
    if (after != null) {
      sql.append(" AND (received_at, id) < (?, ?)");
      params.add(Timestamp.from(after.receivedAt()));
      params.add(after.id());
    }
    sql.append(" ORDER BY received_at DESC, id DESC LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> row(rs)).list();
  }

  @Override
  public boolean dismiss(TenantId tenant, UUID id, String reason, UUID actor, Instant at) {
    return jdbc.sql(
                "UPDATE task.incoming_doc SET status = 'DA_BO_QUA', dismiss_reason = ?,"
                    + " dismissed_by = ?, dismissed_at = ?, updated_by = ?, updated_at = ?,"
                    + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?"
                    + " AND status = 'CHO_XU_LY'")
            .params(
                reason, actor, Timestamp.from(at), actor, Timestamp.from(at), tenant.value(), id)
            .update()
        == 1;
  }

  @Override
  public void markProcessed(TenantId tenant, UUID id, UUID actor, Instant at) {
    jdbc.sql(
            "UPDATE task.incoming_doc SET status = 'DA_XU_LY', updated_by = ?, updated_at = ?,"
                + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?"
                + " AND status = 'CHO_XU_LY'")
        .params(actor, Timestamp.from(at), tenant.value(), id)
        .update();
  }

  @Override
  public Optional<Claim> claim(
      TenantId tenant,
      UUID docId,
      int paragraphNo,
      UUID taskId,
      int periodBucket,
      String taskCode,
      String kind,
      UUID actor) {
    int inserted =
        jdbc.sql(
                "INSERT INTO task.incoming_doc_claim (tenant_id, incoming_doc_id, paragraph_no,"
                    + " created_by, task_id, period_bucket, task_code, kind)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(tenant.value(), docId, paragraphNo, actor, taskId, periodBucket, taskCode, kind)
            .update();
    if (inserted == 1) {
      return Optional.empty();
    }
    return jdbc.sql(
            "SELECT paragraph_no, task_id, period_bucket, task_code, kind"
                + " FROM task.incoming_doc_claim WHERE tenant_id = ? AND incoming_doc_id = ?"
                + " AND paragraph_no = ?")
        .params(tenant.value(), docId, paragraphNo)
        .query((rs, i) -> claim(rs))
        .optional();
  }

  @Override
  public List<Claim> claims(TenantId tenant, UUID docId) {
    return jdbc.sql(
            "SELECT paragraph_no, task_id, period_bucket, task_code, kind"
                + " FROM task.incoming_doc_claim WHERE tenant_id = ? AND incoming_doc_id = ?"
                + " ORDER BY paragraph_no")
        .params(tenant.value(), docId)
        .query((rs, i) -> claim(rs))
        .list();
  }

  @Override
  public void link(TenantId tenant, Link l, UUID actor) {
    DocRow d = l.doc();
    jdbc.sql(
            "INSERT INTO task.task_source_doc (id, tenant_id, created_by, updated_by, period_id,"
                + " period_bucket, task_id, source_system, external_doc_id, doc_number, doc_date,"
                + " excerpt, received_event_id, ai_extracted, paragraph_no, incoming_doc_id,"
                + " suggestion_id, ai_fields_kept, ai_fields_edited, similar_reason)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,"
                + " ?)")
        .params(
            UUID.randomUUID(),
            tenant.value(),
            actor,
            actor,
            l.periodId(),
            l.periodBucket(),
            l.taskId(),
            d.sourceSystem(),
            d.externalId(),
            d.docNumber(),
            d.docDate(),
            d.excerpt(),
            d.eventId(),
            l.aiExtracted(),
            l.paragraphNo(),
            d.id(),
            l.suggestionId(),
            write(l.kept()),
            write(l.edited()),
            l.similarReason())
        .update();
  }

  @Override
  public Optional<Linked> linkOf(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT s.incoming_doc_id, d.recipient_unit_id, s.paragraph_no, s.ai_extracted,"
                + " s.similar_reason FROM task.task_source_doc s JOIN task.incoming_doc d"
                + " ON d.tenant_id = s.tenant_id AND d.id = s.incoming_doc_id"
                + " WHERE s.period_bucket = ? AND s.tenant_id = ? AND s.task_id = ?"
                + " ORDER BY s.created_at LIMIT 1")
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) ->
                new Linked(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getInt(3),
                    rs.getBoolean(4),
                    rs.getString(5)))
        .optional();
  }

  @Override
  public Optional<TaskRef> task(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT id, period_id, period_bucket, code, title, status, source, org_unit_id,"
                + " row_version FROM task.task WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), taskId)
        .query(
            (rs, i) ->
                new TaskRef(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getObject(8, UUID.class),
                    rs.getLong(9)))
        .optional();
  }

  private DocRow row(ResultSet rs) throws SQLException {
    return new DocRow(
        rs.getObject("id", UUID.class),
        rs.getObject("recipient_unit_id", UUID.class),
        rs.getString("source_system"),
        rs.getString("connector_code"),
        rs.getString("external_doc_id"),
        rs.getString("doc_number"),
        rs.getObject("doc_date", LocalDate.class),
        rs.getString("issuer"),
        rs.getString("excerpt"),
        rs.getString("doc_format"),
        rs.getString("issuer_header"),
        rs.getString("title"),
        rs.getString("preamble"),
        read(rs.getString("paragraphs")),
        rs.getString("recipients"),
        rs.getString("classification"),
        rs.getBoolean("content_loaded"),
        rs.getInt("doc_version"),
        rs.getObject("received_event_id", UUID.class),
        rs.getTimestamp("received_at").toInstant(),
        rs.getString("status"),
        rs.getString("dismiss_reason"),
        rs.getLong("row_version"));
  }

  private static Claim claim(ResultSet rs) throws SQLException {
    return new Claim(
        rs.getInt("paragraph_no"),
        rs.getObject("task_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("task_code"),
        rs.getString("kind"));
  }

  private List<Paragraph> read(String text) {
    if (text == null) {
      return List.of();
    }
    try {
      return json.readValue(text, PARAGRAPHS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Đoạn văn bản đến không đọc được", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String cut(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }
}
