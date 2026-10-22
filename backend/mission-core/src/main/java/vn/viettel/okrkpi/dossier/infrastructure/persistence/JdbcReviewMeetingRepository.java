package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.domain.meeting.MeetingStatus;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.dossier.domain.meeting.Proceedings;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link ReviewMeetingRepository} trên {@code dossier.review_meeting}. Cột {@code
 * participants_json} giữ đối tượng {@code {"participants": [{personId, role, status}],
 * "deadlineOverrideReason": ..., "minutesJobId", "closedAt", "closedBy", "signDueDate",
 * "signatures": [...]}}: TL-03 không có cột cho lý do giữ ngày họp và diễn biến sau khi họp nên các
 * giá trị này đi cùng thành phần hội nghị (wiki trang 14, suy luận).
 */
public class JdbcReviewMeetingRepository implements ReviewMeetingRepository {

  private static final String COLUMNS =
      "id, period_id, period_bucket, org_unit_id, meeting_type, scheduled_at, location,"
          + " draft_sent_at, chair_person_id, secretary_person_id, status, meeting_at,"
          + " subject_person_id, participants_json::text AS participants, vote_json::text AS vote,"
          + " minutes_object_key, minutes_sha256, sign_request_id, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcReviewMeetingRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void insert(TenantId tenant, MeetingRow row, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.review_meeting (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, org_unit_id, meeting_type, scheduled_at, location, draft_sent_at,
                chair_person_id, secretary_person_id, status, subject_person_id, participants_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """)
        .params(
            row.id(),
            tenant.value(),
            actor,
            actor,
            row.periodId(),
            row.periodBucket(),
            row.orgUnitId(),
            row.meetingType(),
            ts(row.scheduledAt()),
            row.location(),
            ts(row.draftSentAt()),
            row.chairPersonId(),
            row.secretaryPersonId(),
            row.status().name(),
            row.subjectPersonId(),
            participantsJson(row))
        .update();
  }

  @Override
  public Optional<MeetingRow> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM dossier.review_meeting WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(this::row)
        .optional();
  }

  @Override
  public Optional<MeetingRow> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.review_meeting WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .params(tenant.value(), id)
        .query(this::row)
        .optional();
  }

  @Override
  public boolean lockAndExists(
      TenantId tenant,
      int bucket,
      UUID periodId,
      UUID orgUnitId,
      String meetingType,
      UUID subjectPersonId) {
    String key =
        "dossier.review_meeting:"
            + tenant.value()
            + ":"
            + periodId
            + ":"
            + orgUnitId
            + ":"
            + meetingType
            + ":"
            + subjectPersonId;
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param(key)
        .query()
        .listOfRows();
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM dossier.review_meeting
             WHERE tenant_id = ? AND period_bucket = ? AND period_id = ? AND org_unit_id = ?
               AND meeting_type = ? AND subject_person_id IS NOT DISTINCT FROM CAST(? AS uuid))
            """)
        .params(tenant.value(), bucket, periodId, orgUnitId, meetingType, subjectPersonId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public long update(TenantId tenant, MeetingRow row, long expectedVersion, UUID actor) {
    return jdbc.sql(
            """
            UPDATE dossier.review_meeting
               SET scheduled_at = ?, location = ?, draft_sent_at = ?, status = ?,
                   participants_json = CAST(? AS jsonb), meeting_at = ?,
                   vote_json = CAST(? AS jsonb), minutes_object_key = ?, minutes_sha256 = ?,
                   sign_request_id = ?, updated_at = now(), updated_by = ?,
                   row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
            RETURNING row_version
            """)
        .params(
            ts(row.scheduledAt()),
            row.location(),
            ts(row.draftSentAt()),
            row.status().name(),
            participantsJson(row),
            ts(row.meetingAt()),
            row.voteJson(),
            row.proceedings().minutesObjectKey(),
            row.minutesSha256(),
            row.signRequestId(),
            actor,
            row.periodBucket(),
            tenant.value(),
            row.id(),
            expectedVersion)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  @Override
  public List<MeetingRow> list(TenantId tenant, Filter filter, UUID after, int limit) {
    String caller = filter.caller() == null ? null : filter.caller().toString();
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM dossier.review_meeting
                WHERE tenant_id = ?
                  AND (CAST(? AS uuid) IS NULL OR period_id = ?)
                  AND (CAST(? AS uuid) IS NULL OR org_unit_id = ?)
                  AND (CAST(? AS varchar) IS NULL OR status = ?)
                  AND (org_unit_id = CAST(? AS uuid)
                       OR chair_person_id = CAST(? AS uuid)
                       OR secretary_person_id = CAST(? AS uuid)
                       OR subject_person_id = CAST(? AS uuid)
                       OR participants_json -> 'participants'
                          @> jsonb_build_array(jsonb_build_object('personId', CAST(? AS text))))
                  AND (CAST(? AS uuid) IS NULL OR id > ?)
                ORDER BY id
                LIMIT ?
                """)
        .params(
            tenant.value(),
            filter.periodId(),
            filter.periodId(),
            filter.orgUnitId(),
            filter.orgUnitId(),
            filter.status() == null ? null : filter.status().name(),
            filter.status() == null ? null : filter.status().name(),
            filter.callerUnit(),
            filter.caller(),
            filter.caller(),
            filter.caller(),
            caller,
            after,
            after,
            limit)
        .query(this::row)
        .list();
  }

  @Override
  public Optional<MeetingRow> findByMinutesJob(TenantId tenant, UUID jobId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.review_meeting WHERE tenant_id = ?"
                + " AND participants_json ->> 'minutesJobId' = ?")
        .params(tenant.value(), jobId.toString())
        .query(this::row)
        .optional();
  }

  @Override
  public boolean isOpenSecretaryFor(TenantId tenant, UUID secretary, UUID person) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM dossier.review_meeting
             WHERE tenant_id = ? AND secretary_person_id = ? AND status <> 'DA_KY'
               AND (subject_person_id = ?
                    OR (subject_person_id IS NULL AND participants_json -> 'participants'
                        @> jsonb_build_array(jsonb_build_object('personId', CAST(? AS text))))))
            """)
        .params(tenant.value(), secretary, person, person.toString())
        .query(Boolean.class)
        .single();
  }

  private MeetingRow row(ResultSet rs, int n) throws SQLException {
    JsonNode doc = parse(rs.getString("participants"));
    List<Participant> participants = new ArrayList<>();
    for (JsonNode p : doc.path("participants")) {
      participants.add(
          new Participant(
              UUID.fromString(p.path("personId").asText()),
              p.path("role").asText(),
              p.path("status").isTextual() ? p.path("status").asText() : null));
    }
    JsonNode reason = doc.path("deadlineOverrideReason");
    return new MeetingRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("org_unit_id", UUID.class),
        rs.getString("meeting_type"),
        instant(rs.getTimestamp("scheduled_at")),
        rs.getString("location"),
        instant(rs.getTimestamp("draft_sent_at")),
        rs.getObject("chair_person_id", UUID.class),
        rs.getObject("secretary_person_id", UUID.class),
        MeetingStatus.valueOf(rs.getString("status")),
        instant(rs.getTimestamp("meeting_at")),
        rs.getObject("subject_person_id", UUID.class),
        participants,
        reason.isTextual() ? reason.asText() : null,
        rs.getString("vote"),
        rs.getBytes("minutes_sha256"),
        rs.getObject("sign_request_id", UUID.class),
        proceedings(doc, rs.getString("minutes_object_key")),
        rs.getLong("row_version"));
  }

  private static Proceedings proceedings(JsonNode doc, String objectKey) {
    List<Proceedings.Signature> signatures = new ArrayList<>();
    for (JsonNode s : doc.path("signatures")) {
      signatures.add(
          new Proceedings.Signature(
              UUID.fromString(s.path("personId").asText()),
              uuid(s.path("signRequestId")),
              s.path("signedAt").isTextual() ? Instant.parse(s.path("signedAt").asText()) : null));
    }
    JsonNode closedAt = doc.path("closedAt");
    JsonNode due = doc.path("signDueDate");
    return new Proceedings(
        uuid(doc.path("minutesJobId")),
        objectKey,
        closedAt.isTextual() ? Instant.parse(closedAt.asText()) : null,
        uuid(doc.path("closedBy")),
        due.isTextual() ? LocalDate.parse(due.asText()) : null,
        signatures);
  }

  private static UUID uuid(JsonNode node) {
    return node.isTextual() ? UUID.fromString(node.asText()) : null;
  }

  String participantsJson(MeetingRow row) {
    ObjectNode doc = json.createObjectNode();
    ArrayNode list = doc.putArray("participants");
    for (Participant p : row.participants()) {
      ObjectNode o = list.addObject();
      o.put("personId", p.personId().toString());
      o.put("role", p.role());
      if (p.status() != null) {
        o.put("status", p.status());
      }
    }
    if (row.deadlineOverrideReason() != null) {
      doc.put("deadlineOverrideReason", row.deadlineOverrideReason());
    }
    Proceedings p = row.proceedings();
    putIfPresent(doc, "minutesJobId", p.minutesJobId());
    putIfPresent(doc, "closedAt", p.closedAt());
    putIfPresent(doc, "closedBy", p.closedBy());
    putIfPresent(doc, "signDueDate", p.signDueDate());
    if (!p.signatures().isEmpty()) {
      ArrayNode signed = doc.putArray("signatures");
      for (Proceedings.Signature s : p.signatures()) {
        ObjectNode o = signed.addObject();
        o.put("personId", s.personId().toString());
        putIfPresent(o, "signRequestId", s.signRequestId());
        putIfPresent(o, "signedAt", s.signedAt());
      }
    }
    return doc.toString();
  }

  private static void putIfPresent(ObjectNode doc, String name, Object value) {
    if (value != null) {
      doc.put(name, value.toString());
    }
  }

  private JsonNode parse(String text) {
    if (text == null) {
      return json.createObjectNode();
    }
    try {
      return json.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("participants_json không đọc được", e);
    }
  }

  private static Timestamp ts(Instant at) {
    return at == null ? null : Timestamp.from(at);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
