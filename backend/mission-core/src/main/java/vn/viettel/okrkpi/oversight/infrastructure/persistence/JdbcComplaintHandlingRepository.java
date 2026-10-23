package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlingRepository;
import vn.viettel.okrkpi.oversight.domain.complaint.CloseReason;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandling;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResolution;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.complaint.ExplanationParty;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phần xử lý của {@code oversight.complaint} (cột của V3) và bản ghi {@code
 * oversight.complaint_response} bằng JDBC (CORE-07-03, CORE-07-04). Điều chỉnh đề nghị lưu dạng
 * mảng ở bản nháp kết luận, dạng đối tượng {@code {"resolution", "items"}} ở bản ghi kết luận vì
 * ràng buộc của V1.
 */
public class JdbcComplaintHandlingRepository implements ComplaintHandlingRepository {

  private static final String HANDLING =
      ", handler_person_id, accepted_at, close_reason_code, close_reason, closed_at,"
          + " conclusion_resolution, conclusion_adjustments_json::text AS conclusion_adjustments,"
          + " conclusion_enc, conclusion_dek_id, conclusion_doc_ref, no_explanation_note,"
          + " concluded_by, concluded_at, response_sign_request_id, responded_at,"
          + " reopen_request_id";

  private static final String SELECT =
      "SELECT " + JdbcComplaintRepository.COLUMNS + HANDLING + " FROM oversight.complaint";

  private static final String RESPONSE_COLUMNS =
      "id, complaint_id, period_id, period_bucket, record_kind, responder_id, party_kind,"
          + " party_id, request_id, due_date, response_enc, dek_id, attachment_object_ids,"
          + " response_doc_ref, sign_request_id, outcome, adjustment_json::text AS adjustment_json,"
          + " responded_at";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final JdbcComplaintRepository base;

  public JdbcComplaintHandlingRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
    this.base = new JdbcComplaintRepository(jdbc, json);
  }

  @Override
  public Optional<Handled> find(TenantId tenant, UUID id) {
    return jdbc.sql(SELECT + " WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(id)
        .query(handled())
        .optional();
  }

  @Override
  public Optional<Handled> lock(TenantId tenant, UUID id) {
    return jdbc.sql(SELECT + " WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .param(tenant.value())
        .param(id)
        .query(handled())
        .optional();
  }

  @Override
  public boolean update(
      TenantId tenant, Complaint c, ComplaintHandling h, int expected, UUID actorId) {
    ComplaintConclusion k = h.conclusion();
    return jdbc.sql(
                """
                UPDATE oversight.complaint
                   SET status = ?, handler_person_id = ?, accepted_at = ?, close_reason_code = ?,
                       close_reason = ?, closed_at = ?, conclusion_resolution = ?,
                       conclusion_adjustments_json = ?::jsonb, conclusion_enc = ?,
                       conclusion_dek_id = ?, conclusion_doc_ref = ?, no_explanation_note = ?,
                       concluded_by = ?, concluded_at = ?, response_sign_request_id = ?,
                       responded_at = ?, reopen_request_id = ?, outcome = ?, row_version = ?,
                       updated_at = now(), updated_by = ?
                 WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(c.status() == null ? null : c.status().name())
            .param(h.handlerPersonId())
            .param(ts(h.acceptedAt()))
            .param(h.closeReason() == null ? null : h.closeReason().name())
            .param(h.closeNote())
            .param(ts(h.closedAt()))
            .param(k == null ? null : k.resolution().name())
            .param(k == null ? null : adjustments(k.adjustments()).toString())
            .param(k == null ? null : k.textEnc())
            .param(k == null ? null : k.dekId())
            .param(k == null ? null : k.docRef())
            .param(k == null ? null : k.noExplanationNote())
            .param(k == null ? null : k.concludedBy())
            .param(k == null ? null : ts(k.concludedAt()))
            .param(h.responseSignRequestId())
            .param(ts(h.respondedAt()))
            .param(h.reopenRequestId())
            .param(h.respondedAt() == null || k == null ? null : k.resolution().outcome())
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
  public Optional<Handled> byResponseSignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(SELECT + " WHERE tenant_id = ? AND response_sign_request_id = ?")
        .param(tenant.value())
        .param(signRequestId)
        .query(handled())
        .optional();
  }

  @Override
  public List<Complaint> inScope(
      TenantId tenant,
      Collection<UUID> units,
      UUID handler,
      UUID complainant,
      UUID periodId,
      Set<ComplaintStatus> statuses,
      ComplaintKind kind,
      DeadlineAfter after,
      int limit) {
    StringBuilder sql =
        new StringBuilder(SELECT).append(" WHERE tenant_id = ? AND status IS NOT NULL");
    List<Object> params = new ArrayList<>(List.of(tenant.value()));
    if (units != null && !units.isEmpty()) {
      sql.append(" AND (handler_unit_id = ANY (?::uuid[])");
      params.add(JdbcComplaintRepository.uuidArray(units));
      if (complainant != null) {
        sql.append(" OR complainant_person_id = ?");
        params.add(complainant);
      }
      sql.append(")");
    }
    if (handler != null) {
      sql.append(" AND handler_person_id = ?");
      params.add(handler);
    }
    if (periodId != null) {
      sql.append(" AND period_id = ?");
      params.add(periodId);
    }
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND status = ANY (?::text[])");
      params.add(statuses.stream().map(Enum::name).collect(Collectors.joining(",", "{", "}")));
    }
    if (kind != null) {
      sql.append(" AND kind = ?");
      params.add(kind.name());
    }
    if (after != null) {
      sql.append(" AND (response_deadline, id) > (?, ?)");
      params.add(Date.valueOf(after.deadline()));
      params.add(after.id());
    }
    sql.append(" ORDER BY response_deadline, id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(base.mapper()).list();
  }

  @Override
  public void insertResponse(TenantId tenant, ComplaintResponse r, UUID actorId) {
    Timestamp at = Timestamp.from(r.respondedAt());
    jdbc.sql(
            """
            INSERT INTO oversight.complaint_response
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               period_id, period_bucket, complaint_id, record_kind, responder_id, response_enc,
               dek_id, response_doc_ref, responded_at, sign_request_id, outcome, adjustment_json,
               party_kind, party_id, due_date, request_id, attachment_object_ids)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?,
                    ?::uuid[])
            """)
        .param(r.id())
        .param(tenant.value())
        .param(at)
        .param(actorId)
        .param(at)
        .param(actorId)
        .param(r.periodId())
        .param(r.periodBucket())
        .param(r.complaintId())
        .param(r.kind().name())
        .param(r.responderId())
        .param(r.contentEnc())
        .param(r.dekId())
        .param(r.docRef())
        .param(at)
        .param(r.signRequestId())
        .param(r.resolution() == null ? null : r.resolution().outcome())
        .param(
            r.resolution() == null || !r.resolution().adjusts()
                ? null
                : json.createObjectNode()
                    .put("resolution", r.resolution().name())
                    .set("items", adjustments(r.adjustments()))
                    .toString())
        .param(r.party() == null ? null : r.party().kind().name())
        .param(r.party() == null ? null : r.party().id())
        .param(r.dueDate() == null ? null : Date.valueOf(r.dueDate()))
        .param(r.requestId())
        .param(JdbcComplaintRepository.uuidArray(r.attachmentIds()))
        .update();
  }

  @Override
  public List<ComplaintResponse> responses(
      TenantId tenant,
      Complaint complaint,
      ComplaintRecordKind kind,
      ResponseAfter after,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(RESPONSE_COLUMNS)
            .append(
                " FROM oversight.complaint_response WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND complaint_id = ?");
    List<Object> params =
        new ArrayList<>(List.of(complaint.periodBucket(), tenant.value(), complaint.id()));
    if (kind != null) {
      sql.append(" AND record_kind = ?");
      params.add(kind.name());
    }
    if (after != null) {
      sql.append(" AND (responded_at, id) > (?, ?)");
      params.add(Timestamp.from(after.respondedAt()));
      params.add(after.id());
    }
    sql.append(" ORDER BY responded_at, id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(this::response).list();
  }

  @Override
  public List<UUID> explanationsSettled(TenantId tenant, LocalDate today, int limit) {
    return jdbc.sql(
            """
            SELECT c.id FROM oversight.complaint c
             WHERE c.tenant_id = ? AND c.status = 'DANG_GIAI_TRINH'
               AND NOT EXISTS (
                     SELECT 1 FROM oversight.complaint_response q
                      WHERE q.period_bucket = c.period_bucket AND q.tenant_id = c.tenant_id
                        AND q.complaint_id = c.id AND q.record_kind = 'YEU_CAU'
                        AND q.due_date >= ?
                        AND NOT EXISTS (
                              SELECT 1 FROM oversight.complaint_response a
                               WHERE a.period_bucket = q.period_bucket
                                 AND a.tenant_id = q.tenant_id AND a.complaint_id = q.complaint_id
                                 AND a.record_kind = 'GIAI_TRINH' AND a.request_id = q.id))
             ORDER BY c.id LIMIT ?
            """)
        .param(tenant.value())
        .param(Date.valueOf(today))
        .param(limit)
        .query(UUID.class)
        .list();
  }

  // ---------------------------------------------------------------- ánh xạ

  private RowMapper<Handled> handled() {
    RowMapper<Complaint> complaint = base.mapper();
    return (rs, row) -> new Handled(complaint.mapRow(rs, row), handling(rs));
  }

  private ComplaintHandling handling(ResultSet rs) throws SQLException {
    String resolution = rs.getString("conclusion_resolution");
    ComplaintConclusion conclusion =
        resolution == null
            ? null
            : new ComplaintConclusion(
                ComplaintResolution.valueOf(resolution),
                adjustments(rs.getString("conclusion_adjustments")),
                rs.getBytes("conclusion_enc"),
                rs.getObject("conclusion_dek_id", UUID.class),
                rs.getString("conclusion_doc_ref"),
                rs.getString("no_explanation_note"),
                rs.getObject("concluded_by", UUID.class),
                JdbcComplaintRepository.instant(rs, "concluded_at"));
    String close = rs.getString("close_reason_code");
    return new ComplaintHandling(
        rs.getObject("handler_person_id", UUID.class),
        JdbcComplaintRepository.instant(rs, "accepted_at"),
        close == null ? null : CloseReason.valueOf(close),
        rs.getString("close_reason"),
        JdbcComplaintRepository.instant(rs, "closed_at"),
        conclusion,
        rs.getObject("response_sign_request_id", UUID.class),
        JdbcComplaintRepository.instant(rs, "responded_at"),
        rs.getObject("reopen_request_id", UUID.class));
  }

  private ComplaintResponse response(ResultSet rs, int row) throws SQLException {
    String partyKind = rs.getString("party_kind");
    Date due = rs.getDate("due_date");
    String outcome = rs.getString("outcome");
    ComplaintResolution resolution = null;
    List<ComplaintAdjustment> items = List.of();
    String raw = rs.getString("adjustment_json");
    if (raw != null) {
      JsonNode n = read(raw);
      resolution = ComplaintResolution.valueOf(n.path("resolution").asText("CHAP_NHAN"));
      items = adjustments(n.path("items"));
    } else if ("GIU_NGUYEN".equals(outcome)) {
      resolution = ComplaintResolution.GIU_NGUYEN;
    }
    return new ComplaintResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("complaint_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        ComplaintRecordKind.valueOf(rs.getString("record_kind")),
        rs.getObject("responder_id", UUID.class),
        partyKind == null
            ? null
            : new ExplanationParty(
                ExplanationParty.Kind.valueOf(partyKind), rs.getObject("party_id", UUID.class)),
        rs.getObject("request_id", UUID.class),
        due == null ? null : due.toLocalDate(),
        rs.getBytes("response_enc"),
        rs.getObject("dek_id", UUID.class),
        JdbcComplaintRepository.uuids(rs.getArray("attachment_object_ids")),
        rs.getString("response_doc_ref"),
        rs.getObject("sign_request_id", UUID.class),
        resolution,
        items,
        JdbcComplaintRepository.instant(rs, "responded_at"));
  }

  private ArrayNode adjustments(List<ComplaintAdjustment> items) {
    ArrayNode a = json.createArrayNode();
    for (ComplaintAdjustment x : items) {
      ObjectNode o = a.addObject().put("targetKey", x.targetKey());
      value(o, "oldValue", x.oldValue());
      value(o, "newValue", x.newValue());
      if (x.reason() != null) {
        o.put("reason", x.reason());
      }
    }
    return a;
  }

  private static void value(ObjectNode o, String field, Object v) {
    if (v instanceof BigDecimal d) {
      o.put(field, d);
    } else if (v != null) {
      o.put(field, v.toString());
    }
  }

  private List<ComplaintAdjustment> adjustments(String raw) {
    return raw == null ? List.of() : adjustments(read(raw));
  }

  private static List<ComplaintAdjustment> adjustments(JsonNode array) {
    List<ComplaintAdjustment> out = new ArrayList<>();
    for (JsonNode n : array) {
      out.add(
          new ComplaintAdjustment(
              n.path("targetKey").asText(),
              scalar(n.get("oldValue")),
              scalar(n.get("newValue")),
              n.hasNonNull("reason") ? n.get("reason").asText() : null));
    }
    return out;
  }

  private static Object scalar(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    return n.isNumber() ? n.decimalValue() : n.asText();
  }

  private JsonNode read(String raw) {
    try {
      return json.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON điều chỉnh của khiếu nại hỏng", e);
    }
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
