package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link RemediationItemRepository} trên {@code dossier.remediation_item}. Hạn chế kỳ trước tra
 * theo người qua chỉ mục {@code ix_remediation_item__person} trên mọi phân vùng kỳ.
 */
public class JdbcRemediationItemRepository implements RemediationItemRepository {

  private static final String COLUMNS =
      "id, period_id, period_bucket, person_id, meeting_id, review_comment_id, content_enc,"
          + " dek_id, due_period_id, status, verified_by, verified_at, row_version";

  private final JdbcClient jdbc;

  public JdbcRemediationItemRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenant, ItemRow row, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.remediation_item (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, person_id, meeting_id, review_comment_id, content_enc, dek_id,
                due_period_id, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            row.id(),
            tenant.value(),
            actor,
            actor,
            row.periodId(),
            row.periodBucket(),
            row.personId(),
            row.meetingId(),
            row.reviewCommentId(),
            row.cipher(),
            row.dekId(),
            row.duePeriodId(),
            row.status())
        .update();
  }

  @Override
  public boolean existsForComment(TenantId tenant, int bucket, UUID reviewCommentId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM dossier.remediation_item
             WHERE period_bucket = ? AND tenant_id = ? AND review_comment_id = ?)
            """)
        .params(bucket, tenant.value(), reviewCommentId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<ItemRow> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.remediation_item WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .params(tenant.value(), id)
        .query(JdbcRemediationItemRepository::row)
        .optional();
  }

  @Override
  public long update(TenantId tenant, ItemRow row, long expectedVersion, UUID actor) {
    return jdbc.sql(
            """
            UPDATE dossier.remediation_item
               SET status = ?, verified_by = ?, verified_at = ?, updated_at = now(), updated_by = ?,
                   row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
            RETURNING row_version
            """)
        .params(
            row.status(),
            row.verifiedBy(),
            row.verifiedAt() == null ? null : Timestamp.from(row.verifiedAt()),
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
  public List<ItemRow> listForMeeting(
      TenantId tenant,
      UUID meetingId,
      UUID periodId,
      List<UUID> persons,
      Origin origin,
      UUID after,
      int limit) {
    boolean previous = origin == null || origin == Origin.KY_TRUOC;
    boolean own = origin == null || origin == Origin.HOI_NGHI_NAY;
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM dossier.remediation_item
                WHERE tenant_id = :tenant
                  AND ((:own AND meeting_id = :meeting)
                       OR (:previous AND person_id IN (:persons)
                           AND meeting_id <> :meeting AND period_id <> :period))
                  AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
                ORDER BY id
                LIMIT :limit
                """)
        .param("tenant", tenant.value())
        .param("own", own)
        .param("meeting", meetingId)
        .param("previous", previous)
        .param("persons", persons.isEmpty() ? List.of(new UUID(0, 0)) : persons)
        .param("period", periodId)
        .param("after", after)
        .param("limit", limit)
        .query(JdbcRemediationItemRepository::row)
        .list();
  }

  private static ItemRow row(ResultSet rs, int n) throws SQLException {
    Timestamp verified = rs.getTimestamp("verified_at");
    return new ItemRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("person_id", UUID.class),
        rs.getObject("meeting_id", UUID.class),
        rs.getObject("review_comment_id", UUID.class),
        rs.getBytes("content_enc"),
        rs.getObject("dek_id", UUID.class),
        rs.getObject("due_period_id", UUID.class),
        rs.getString("status"),
        rs.getObject("verified_by", UUID.class),
        verified == null ? null : verified.toInstant(),
        rs.getLong("row_version"));
  }
}
