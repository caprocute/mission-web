package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link ReviewCommentRepository} trên {@code dossier.review_comment} (chỉ thêm). */
public class JdbcReviewCommentRepository implements ReviewCommentRepository {

  private final JdbcClient jdbc;

  public JdbcReviewCommentRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenant, CommentRow row, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.review_comment (id, tenant_id, created_at, created_by, updated_at,
                updated_by, period_id, period_bucket, meeting_id, subject_person_id,
                commenter_person_id, comment_kind, kind, phase, comment_enc, dek_id, remediation_due)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            row.id(),
            tenant.value(),
            Timestamp.from(row.createdAt()),
            actor,
            Timestamp.from(row.createdAt()),
            actor,
            row.periodId(),
            row.periodBucket(),
            row.meetingId(),
            row.subjectPersonId(),
            row.commenterPersonId(),
            row.source(),
            row.kind(),
            row.phase(),
            row.cipher(),
            row.dekId(),
            row.remediationDue() == null ? null : Date.valueOf(row.remediationDue()))
        .update();
  }

  @Override
  public List<CommentRow> list(
      TenantId tenant,
      int bucket,
      UUID meetingId,
      String phase,
      String kind,
      Position after,
      int limit) {
    Timestamp afterAt = after == null ? null : Timestamp.from(after.createdAt());
    UUID afterId = after == null ? null : after.id();
    return jdbc.sql(
            """
            SELECT id, meeting_id, period_id, period_bucket, subject_person_id, commenter_person_id,
                   comment_kind, kind, phase, comment_enc, dek_id, remediation_due, created_at
              FROM dossier.review_comment
             WHERE period_bucket = ? AND tenant_id = ? AND meeting_id = ?
               AND (CAST(? AS varchar) IS NULL OR phase = ?)
               AND (CAST(? AS varchar) IS NULL OR kind = ?)
               AND (CAST(? AS timestamptz) IS NULL
                    OR (created_at, id) > (CAST(? AS timestamptz), CAST(? AS uuid)))
             ORDER BY created_at, id
             LIMIT ?
            """)
        .params(
            bucket,
            tenant.value(),
            meetingId,
            phase,
            phase,
            kind,
            kind,
            afterAt,
            afterAt,
            afterId,
            limit)
        .query(JdbcReviewCommentRepository::row)
        .list();
  }

  private static CommentRow row(ResultSet rs, int n) throws SQLException {
    Date due = rs.getDate("remediation_due");
    return new CommentRow(
        rs.getObject("id", UUID.class),
        rs.getObject("meeting_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("subject_person_id", UUID.class),
        rs.getObject("commenter_person_id", UUID.class),
        rs.getString("comment_kind"),
        rs.getString("kind"),
        rs.getString("phase"),
        rs.getBytes("comment_enc"),
        rs.getObject("dek_id", UUID.class),
        due == null ? null : due.toLocalDate(),
        rs.getTimestamp("created_at").toInstant());
  }
}
