package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.ComplaintProcessRepository;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintAdjustment;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintConclusion;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintRecordKind;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResolution;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintResponse;
import vn.viettel.okrkpi.oversight.domain.complaint.ExplanationParty;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Giải trình và kết luận khiếu nại bằng JDBC (CORE-07-04, migration oversight V6). Điều chỉnh đề
 * nghị lưu dạng mảng ở cột kết luận của khiếu nại, dạng đối tượng {@code {"resolution", "items"}} ở
 * bản ghi {@code KET_LUAN} vì ràng buộc {@code ck_complaint_response__adjustment} của V1.
 */
public class JdbcComplaintProcessRepository implements ComplaintProcessRepository {

  private static final String CONCLUSION =
      "SELECT conclusion_resolution, conclusion_adjustments_json::text AS adjustments,"
          + " conclusion_enc, conclusion_dek_id, conclusion_doc_ref, no_explanation_note,"
          + " concluded_by, concluded_at, reopen_request_id, response_sign_request_id,"
          + " responded_at, (SELECT max(r.responded_at) FROM oversight.complaint_response r"
          + " WHERE r.period_bucket = c.period_bucket AND r.tenant_id = c.tenant_id"
          + " AND r.complaint_id = c.id AND r.record_kind = 'BAO_LUU') AS reserved_at"
          + " FROM oversight.complaint c"
          + " WHERE c.period_bucket = ? AND c.tenant_id = ? AND c.id = ?";

  private static final String RESPONSE_COLUMNS =
      "SELECT id, complaint_id, period_id, period_bucket, record_kind, responder_id, party_kind,"
          + " party_id, request_id, due_date, response_enc, dek_id, attachment_object_ids,"
          + " response_doc_ref, sign_request_id, outcome, adjustment_json::text AS adjustment_json,"
          + " responded_at FROM oversight.complaint_response";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcComplaintProcessRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<ComplaintConclusion> conclusion(TenantId tenant, Complaint c) {
    return jdbc.sql(CONCLUSION)
        .param(c.periodBucket())
        .param(tenant.value())
        .param(c.id())
        .query(
            (rs, row) -> {
              String resolution = rs.getString("conclusion_resolution");
              if (resolution == null) {
                return Optional.<ComplaintConclusion>empty();
              }
              return Optional.of(
                  new ComplaintConclusion(
                      ComplaintResolution.valueOf(resolution),
                      adjustments(rs.getString("adjustments")),
                      rs.getBytes("conclusion_enc"),
                      rs.getObject("conclusion_dek_id", UUID.class),
                      rs.getString("conclusion_doc_ref"),
                      rs.getString("no_explanation_note"),
                      rs.getObject("concluded_by", UUID.class),
                      instant(rs, "concluded_at"),
                      rs.getObject("reopen_request_id", UUID.class),
                      rs.getObject("response_sign_request_id", UUID.class),
                      instant(rs, "responded_at"),
                      instant(rs, "reserved_at")));
            })
        .optional()
        .flatMap(o -> o);
  }

  @Override
  public void saveConclusion(TenantId tenant, Complaint c, ComplaintConclusion k) {
    jdbc.sql(
            """
            UPDATE oversight.complaint
               SET conclusion_resolution = ?, conclusion_adjustments_json = ?::jsonb,
                   conclusion_enc = ?, conclusion_dek_id = ?, conclusion_doc_ref = ?,
                   no_explanation_note = ?, concluded_by = ?, concluded_at = ?,
                   reopen_request_id = ?, response_sign_request_id = ?, responded_at = ?,
                   outcome = ?
             WHERE period_bucket = ? AND tenant_id = ? AND id = ?
            """)
        .param(k == null ? null : k.resolution().name())
        .param(k == null ? null : adjustments(k.adjustments()).toString())
        .param(k == null ? null : k.textEnc())
        .param(k == null ? null : k.dekId())
        .param(k == null ? null : k.docRef())
        .param(k == null ? null : k.noExplanationNote())
        .param(k == null ? null : k.concludedBy())
        .param(k == null ? null : ts(k.concludedAt()))
        .param(k == null ? null : k.reopenRequestId())
        .param(k == null ? null : k.signRequestId())
        .param(k == null ? null : ts(k.respondedAt()))
        .param(k == null || k.respondedAt() == null ? null : k.resolution().outcome())
        .param(c.periodBucket())
        .param(tenant.value())
        .param(c.id())
        .update();
  }

  @Override
  public Optional<UUID> byResponseSignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            "SELECT id FROM oversight.complaint WHERE tenant_id = ? AND response_sign_request_id = ?")
        .param(tenant.value())
        .param(signRequestId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public void insertResponse(TenantId tenant, ComplaintResponse r, UUID actorId) {
    Timestamp at = Timestamp.from(r.respondedAt());
    ComplaintResolution resolution = r.resolution();
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
        .param(resolution == null ? null : resolution.outcome())
        .param(
            resolution == null || !resolution.adjusts()
                ? null
                : json.createObjectNode()
                    .put("resolution", resolution.name())
                    .set("items", adjustments(r.adjustments()))
                    .toString())
        .param(r.party() == null ? null : r.party().kind().name())
        .param(r.party() == null ? null : r.party().id())
        .param(r.dueDate() == null ? null : Date.valueOf(r.dueDate()))
        .param(r.requestId())
        .param(uuidArray(r.attachmentIds()))
        .update();
  }

  @Override
  public Optional<ComplaintResponse> response(TenantId tenant, Complaint c, UUID id) {
    return jdbc.sql(
            RESPONSE_COLUMNS
                + " WHERE period_bucket = ? AND tenant_id = ? AND complaint_id = ? AND id = ?")
        .param(c.periodBucket())
        .param(tenant.value())
        .param(c.id())
        .param(id)
        .query(this::response)
        .optional();
  }

  @Override
  public List<ComplaintResponse> responses(
      TenantId tenant, Complaint c, ComplaintRecordKind kind, ResponseAfter after, int limit) {
    StringBuilder sql =
        new StringBuilder(RESPONSE_COLUMNS)
            .append(" WHERE period_bucket = ? AND tenant_id = ? AND complaint_id = ?");
    List<Object> params = new ArrayList<>(List.of(c.periodBucket(), tenant.value(), c.id()));
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
  public void reserve(TenantId tenant, Complaint c, byte[] opinionEnc) {
    jdbc.sql(
            """
            UPDATE oversight.complaint SET reserve_opinion_enc = ?, outcome = 'BAO_LUU'
             WHERE period_bucket = ? AND tenant_id = ? AND id = ?
            """)
        .param(opinionEnc)
        .param(c.periodBucket())
        .param(tenant.value())
        .param(c.id())
        .update();
  }

  @Override
  public List<UUID> closable(TenantId tenant, Instant respondedBefore, int limit) {
    return jdbc.sql(
            """
            SELECT c.id FROM oversight.complaint c
             WHERE c.tenant_id = ?
               AND ((c.status = 'DA_TRA_LOI' AND c.responded_at < ?)
                 OR c.status = 'KHONG_THU_LY'
                 OR (c.status = 'DANG_THI_HANH' AND EXISTS (
                       SELECT 1 FROM oversight.reopen_request q
                        WHERE q.tenant_id = c.tenant_id AND q.id = c.reopen_request_id
                          AND q.status = 'DA_THUC_HIEN' AND q.relocked_at IS NOT NULL)))
             ORDER BY c.id
             LIMIT ?
            """)
        .param(tenant.value())
        .param(ts(respondedBefore))
        .param(limit)
        .query(UUID.class)
        .list();
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
        uuids(rs.getArray("attachment_object_ids")),
        rs.getString("response_doc_ref"),
        rs.getObject("sign_request_id", UUID.class),
        resolution,
        items,
        instant(rs, "responded_at"));
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

  private static List<UUID> uuids(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) array.getArray()).map(v -> (UUID) v).toList();
  }

  private static String uuidArray(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
