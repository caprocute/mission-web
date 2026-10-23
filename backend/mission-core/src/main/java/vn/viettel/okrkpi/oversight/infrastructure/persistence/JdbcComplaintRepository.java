package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStage;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code oversight.complaint} bằng JDBC (CORE-07-02); nháp có {@code status} null. */
public class JdbcComplaintRepository implements ComplaintRepository {

  static final String COLUMNS =
      "id, code, period_id, period_bucket, complainant_person_id, profile_id, kind, target_stage,"
          + " subject_line, targets_json::text AS targets_json, content_enc, request_text_enc,"
          + " dek_id, attachment_object_ids, reserve_opinion, status, filed_at,"
          + " response_deadline, handler_unit_id, sign_request_id, created_at, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcComplaintRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void lockCodes(TenantId tenant, int year) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("oversight.complaint.code:" + tenant.value() + ":" + year)
        .query()
        .singleValue();
  }

  @Override
  public List<String> codesOfYear(TenantId tenant, int year) {
    return jdbc.sql("SELECT code FROM oversight.complaint WHERE tenant_id = ? AND code LIKE ?")
        .param(tenant.value())
        .param("KN-" + year + "-%")
        .query(String.class)
        .list();
  }

  @Override
  public void insert(TenantId tenant, Complaint c, UUID actorId) {
    Timestamp now = Timestamp.from(c.createdAt());
    jdbc.sql(
            """
            INSERT INTO oversight.complaint
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               period_id, period_bucket, code, complainant_person_id, profile_id, kind,
               target_stage, subject_line, targets_json, content_enc, request_text_enc, dek_id,
               attachment_object_ids, reserve_opinion)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::uuid[], ?)
            """)
        .param(c.id())
        .param(tenant.value())
        .param(now)
        .param(actorId)
        .param(now)
        .param(actorId)
        .param(c.rowVersion())
        .param(c.periodId())
        .param(c.periodBucket())
        .param(c.code())
        .param(c.complainantId())
        .param(c.profileId())
        .param(c.kind().name())
        .param(c.targetStage().name())
        .param(c.subjectLine())
        .param(targetsJson(c.targets()))
        .param(c.contentEnc())
        .param(c.requestTextEnc())
        .param(c.dekId())
        .param(uuidArray(c.attachmentIds()))
        .param(c.reserveOpinion())
        .update();
  }

  @Override
  public Optional<Complaint> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM oversight.complaint WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(id)
        .query(mapper())
        .optional();
  }

  @Override
  public Optional<Complaint> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.complaint WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .param(tenant.value())
        .param(id)
        .query(mapper())
        .optional();
  }

  @Override
  public boolean update(TenantId tenant, Complaint c, int expected, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE oversight.complaint
                   SET kind = ?, target_stage = ?, subject_line = ?, targets_json = ?::jsonb,
                       content_enc = ?, request_text_enc = ?, dek_id = ?,
                       attachment_object_ids = ?::uuid[], reserve_opinion = ?, status = ?,
                       filed_at = ?, response_deadline = ?, handler_unit_id = ?,
                       sign_request_id = ?, row_version = ?, updated_at = now(), updated_by = ?
                 WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(c.kind().name())
            .param(c.targetStage().name())
            .param(c.subjectLine())
            .param(targetsJson(c.targets()))
            .param(c.contentEnc())
            .param(c.requestTextEnc())
            .param(c.dekId())
            .param(uuidArray(c.attachmentIds()))
            .param(c.reserveOpinion())
            .param(c.status() == null ? null : c.status().name())
            .param(c.filedAt() == null ? null : Timestamp.from(c.filedAt()))
            .param(c.responseDeadline() == null ? null : Date.valueOf(c.responseDeadline()))
            .param(c.handlerUnitId())
            .param(c.signRequestId())
            .param(c.rowVersion())
            .param(actorId)
            .param(c.periodBucket())
            .param(tenant.value())
            .param(c.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public List<Complaint> ofComplainant(
      TenantId tenant,
      UUID personId,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      boolean includeDrafts,
      ComplaintKind kind,
      After after,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM oversight.complaint WHERE tenant_id = ? AND complainant_person_id = ?");
    List<Object> params = new ArrayList<>(List.of(tenant.value(), personId));
    if (periodId != null) {
      sql.append(" AND period_id = ?");
      params.add(periodId);
    }
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND status = ANY (?::text[])");
      params.add(statuses.stream().map(Enum::name).collect(Collectors.joining(",", "{", "}")));
    } else if (!includeDrafts) {
      sql.append(" AND status IS NOT NULL");
    }
    if (kind != null) {
      sql.append(" AND kind = ?");
      params.add(kind.name());
    }
    if (after != null) {
      sql.append(" AND (created_at, id) < (?, ?)");
      params.add(Timestamp.from(after.createdAt()));
      params.add(after.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(mapper()).list();
  }

  @Override
  public Optional<Complaint> bySignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.complaint WHERE tenant_id = ? AND sign_request_id = ?")
        .param(tenant.value())
        .param(signRequestId)
        .query(mapper())
        .optional();
  }

  RowMapper<Complaint> mapper() {
    return (ResultSet rs, int row) -> {
      String status = rs.getString("status");
      Date deadline = rs.getDate("response_deadline");
      return new Complaint(
          rs.getObject("id", UUID.class),
          rs.getString("code"),
          rs.getObject("period_id", UUID.class),
          rs.getInt("period_bucket"),
          rs.getObject("complainant_person_id", UUID.class),
          rs.getObject("profile_id", UUID.class),
          ComplaintKind.valueOf(rs.getString("kind")),
          ComplaintStage.valueOf(rs.getString("target_stage")),
          rs.getString("subject_line"),
          targets(rs.getString("targets_json")),
          rs.getBytes("content_enc"),
          rs.getBytes("request_text_enc"),
          rs.getObject("dek_id", UUID.class),
          uuids(rs.getArray("attachment_object_ids")),
          rs.getBoolean("reserve_opinion"),
          status == null ? null : ComplaintStatus.valueOf(status),
          instant(rs, "filed_at"),
          deadline == null ? null : deadline.toLocalDate(),
          rs.getObject("handler_unit_id", UUID.class),
          rs.getObject("sign_request_id", UUID.class),
          instant(rs, "created_at"),
          rs.getInt("row_version"));
    };
  }

  private String targetsJson(List<ComplaintTarget> targets) {
    ArrayNode a = json.createArrayNode();
    for (ComplaintTarget t : targets) {
      ObjectNode o = a.addObject().put("key", t.key()).put("stage", t.stage().name());
      if (t.label() != null) {
        o.put("label", t.label());
      }
    }
    return a.toString();
  }

  private List<ComplaintTarget> targets(String raw) {
    List<ComplaintTarget> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    try {
      for (JsonNode n : json.readTree(raw)) {
        out.add(
            new ComplaintTarget(
                n.get("key").asText(),
                ComplaintStage.valueOf(n.get("stage").asText()),
                n.hasNonNull("label") ? n.get("label").asText() : null));
      }
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("targets_json hỏng", e);
    }
    return out;
  }

  static List<UUID> uuids(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object[] values = (Object[]) array.getArray();
    return Arrays.stream(values).map(v -> (UUID) v).toList();
  }

  static String uuidArray(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
