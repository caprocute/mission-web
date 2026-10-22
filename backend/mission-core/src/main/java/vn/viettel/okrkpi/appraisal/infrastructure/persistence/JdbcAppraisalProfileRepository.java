package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalProfile;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingRecorded;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu hồ sơ đánh giá bằng {@code appraisalDataSource} (vai trò {@code svc_appraisal}, RLS theo
 * {@code app.tenant_id}). Mức xếp loại chỉ chèn mới và thôi hiện hành, không sửa tại chỗ (TL-03).
 */
public class JdbcAppraisalProfileRepository implements AppraisalProfileRepository {

  private static final String PROFILE_SQL =
      """
      SELECT p.id, p.period_id, p.period_bucket, p.person_id, p.flow, p.status, p.appraiser_id,
             p.decider_id, p.current_snapshot_id, s.auto_rating_code, p.final_rating_code,
             p.return_count, p.policy_pack_version_id, p.row_version
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.score_snapshot s
          ON s.period_bucket = p.period_bucket AND s.tenant_id = p.tenant_id
         AND s.id = p.current_snapshot_id
       WHERE p.tenant_id = ? AND p.id = ?
         FOR UPDATE OF p
      """;

  private static final String RATINGS_SQL =
      """
      SELECT rp.id, rp.level_kind, rp.rating_code, rp.actor_id, rp.explanation_required,
             rp.reason_enc IS NOT NULL,
             EXISTS (SELECT 1 FROM appraisal.rating_explanation e
                      WHERE e.period_bucket = rp.period_bucket AND e.tenant_id = rp.tenant_id
                        AND e.rating_proposal_id = rp.id)
        FROM appraisal.rating_proposal rp
       WHERE rp.period_bucket = ? AND rp.tenant_id = ? AND rp.profile_id = ? AND rp.is_current
      """;

  private final JdbcClient jdbc;

  public JdbcAppraisalProfileRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<ProfileRow> lock(TenantId tenant, UUID profileId) {
    return jdbc.sql(PROFILE_SQL)
        .params(tenant.value(), profileId)
        .query((rs, i) -> head(rs))
        .optional()
        .map(
            h ->
                new ProfileRow(
                    h.id(),
                    h.periodId(),
                    h.periodBucket(),
                    h.personId(),
                    h.flow(),
                    h.status(),
                    h.appraiserId(),
                    h.deciderId(),
                    h.currentSnapshotId(),
                    h.autoRating(),
                    h.finalRating(),
                    h.returnCount(),
                    h.policyPackVersionId(),
                    h.rowVersion(),
                    ratings(tenant, h.periodBucket(), h.id())));
  }

  @Override
  public Optional<Long> save(
      TenantId tenant, ProfileRow row, AppraisalProfile p, UUID actorId, Instant at) {
    String status = p.status().name();
    Timestamp ts = Timestamp.from(at);
    int n =
        jdbc.sql(
                """
                UPDATE appraisal.appraisal_profile
                   SET status = ?, appraiser_id = ?, decider_id = ?, current_snapshot_id = ?,
                       final_rating_code = ?, return_count = ?,
                       decided_at = CASE WHEN ? = 'DA_QUYET_DINH' THEN ? ELSE decided_at END,
                       submitted_at = CASE WHEN ? = 'CHO_THAM_DINH' AND status = 'TU_DANH_GIA'
                                           THEN ? ELSE submitted_at END,
                       locked_at = CASE WHEN ? = 'DA_KHOA' THEN ? ELSE locked_at END,
                       updated_at = ?, updated_by = ?, row_version = row_version + 1
                 WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
                """)
            .params(
                status,
                p.appraiserId(),
                p.deciderId(),
                p.currentSnapshotId(),
                p.finalRating().map(Enum::name).orElse(null),
                p.returnCount(),
                status,
                ts,
                status,
                ts,
                status,
                ts,
                ts,
                actorId,
                row.periodBucket(),
                tenant.value(),
                row.id(),
                row.rowVersion())
            .update();
    return n == 1 ? Optional.of(row.rowVersion() + 1) : Optional.empty();
  }

  @Override
  public void supersedeRating(
      TenantId tenant, int periodBucket, UUID proposalId, UUID actorId, Instant at) {
    jdbc.sql(
            """
            UPDATE appraisal.rating_proposal
               SET is_current = false, updated_at = ?, updated_by = ?, row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND is_current
            """)
        .params(Timestamp.from(at), actorId, periodBucket, tenant.value(), proposalId)
        .update();
  }

  @Override
  public void insertRating(
      TenantId tenant,
      ProfileRow row,
      UUID snapshotId,
      RatingRecorded recorded,
      SealedText reason,
      SealedText comment,
      Instant at) {
    insertRating(tenant, row, snapshotId, recorded, reason, comment, at, null);
  }

  @Override
  public void insertRating(
      TenantId tenant,
      ProfileRow row,
      UUID snapshotId,
      RatingRecorded recorded,
      SealedText reason,
      SealedText comment,
      Instant at,
      UUID batchDecisionId) {
    CurrentRating r = recorded.rating();
    UUID dek = reason != null ? reason.dekId() : comment != null ? comment.dekId() : null;
    jdbc.sql(
            """
            INSERT INTO appraisal.rating_proposal (id, tenant_id, created_at, created_by, updated_at,
                updated_by, period_id, period_bucket, profile_id, level_kind, rating_code,
                score_snapshot_id, actor_id, actor_role, is_current, differs_from_prev,
                explanation_required, reason_enc, comment_enc, dek_id, proposed_at,
                is_bulk_decision, batch_decision_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            r.proposalId(),
            tenant.value(),
            Timestamp.from(at),
            r.actorId(),
            Timestamp.from(at),
            r.actorId(),
            row.periodId(),
            row.periodBucket(),
            row.id(),
            r.levelKind().name(),
            r.rating().name(),
            snapshotId,
            r.actorId(),
            recorded.actorRole(),
            recorded.differsFromPrevious(),
            r.explanationRequired(),
            reason == null ? null : reason.cipher(),
            comment == null ? null : comment.cipher(),
            dek,
            Timestamp.from(at),
            batchDecisionId != null,
            batchDecisionId)
        .update();
  }

  @Override
  public void insertExplanation(
      TenantId tenant,
      ProfileRow row,
      UUID explanationId,
      UUID proposalId,
      String explanationType,
      String autoText,
      String legalRef,
      SealedText explanation,
      UUID actorId,
      Instant at) {
    jdbc.sql(
            """
            INSERT INTO appraisal.rating_explanation (id, tenant_id, created_at, created_by,
                updated_at, updated_by, period_id, period_bucket, rating_proposal_id, profile_id,
                explanation_type, auto_text, legal_ref, explanation_enc, dek_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            explanationId,
            tenant.value(),
            Timestamp.from(at),
            actorId,
            Timestamp.from(at),
            actorId,
            row.periodId(),
            row.periodBucket(),
            proposalId,
            row.id(),
            explanationType,
            autoText,
            legalRef,
            explanation == null ? null : explanation.cipher(),
            explanation == null ? null : explanation.dekId())
        .update();
  }

  private List<CurrentRating> ratings(TenantId tenant, int bucket, UUID profileId) {
    return jdbc.sql(RATINGS_SQL)
        .params(bucket, tenant.value(), profileId)
        .query(
            (rs, i) ->
                new CurrentRating(
                    rs.getObject(1, UUID.class),
                    LevelKind.valueOf(rs.getString(2)),
                    RatingLevel.valueOf(rs.getString(3)),
                    rs.getObject(4, UUID.class),
                    rs.getBoolean(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7)))
        .list();
  }

  private static Head head(ResultSet rs) throws SQLException {
    return new Head(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        rs.getObject(4, UUID.class),
        rs.getString(5),
        AppraisalStatus.valueOf(rs.getString(6)),
        rs.getObject(7, UUID.class),
        rs.getObject(8, UUID.class),
        rs.getObject(9, UUID.class),
        level(rs.getString(10)),
        level(rs.getString(11)),
        rs.getInt(12),
        rs.getObject(13, UUID.class),
        rs.getLong(14));
  }

  private static RatingLevel level(String code) {
    return code == null ? null : RatingLevel.valueOf(code);
  }

  private record Head(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID personId,
      String flow,
      AppraisalStatus status,
      UUID appraiserId,
      UUID deciderId,
      UUID currentSnapshotId,
      RatingLevel autoRating,
      RatingLevel finalRating,
      int returnCount,
      UUID policyPackVersionId,
      long rowVersion) {}
}
