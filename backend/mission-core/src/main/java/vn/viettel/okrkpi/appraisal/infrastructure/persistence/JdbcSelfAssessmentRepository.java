package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiếu tự đánh giá trên {@code appraisalDataSource} (vai trò {@code svc_appraisal}, RLS theo
 * {@code app.tenant_id}); các cột C2 chỉ nhận bản mã phong bì.
 */
public class JdbcSelfAssessmentRepository implements SelfAssessmentRepository {

  private static final String COLUMNS =
      "id, profile_id, version_no, form_code, form_version, structured_data::text, claims_json::text,"
          + " content_enc, strengths_enc, weaknesses_enc, remedial_plan_enc, breakthrough_enc,"
          + " reason_enc, dek_id, content_hash, submitted_at, sign_request_id, signed_at, updated_at";

  private final JdbcClient jdbc;

  public JdbcSelfAssessmentRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<StoredSelfAssessment> current(TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM appraisal.self_assessment WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? ORDER BY version_no DESC LIMIT 1")
        .params(periodBucket, tenant.value(), profileId)
        .query((rs, i) -> stored(rs))
        .optional();
  }

  @Override
  public void insert(
      TenantId tenant, ProfileRow profile, StoredSelfAssessment d, UUID actor, Instant at) {
    Timestamp ts = Timestamp.from(at);
    jdbc.sql(
            """
            INSERT INTO appraisal.self_assessment (id, tenant_id, created_at, created_by, updated_at,
                updated_by, period_id, period_bucket, profile_id, version_no, form_code, form_version,
                structured_data, claims_json, content_enc, strengths_enc, weaknesses_enc,
                remedial_plan_enc, breakthrough_enc, reason_enc, dek_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            d.id(),
            tenant.value(),
            ts,
            actor,
            ts,
            actor,
            profile.periodId(),
            profile.periodBucket(),
            profile.id(),
            d.versionNo(),
            d.formCode(),
            d.formVersion(),
            d.structuredJson(),
            d.claimsJson(),
            cipher(d.content()),
            cipher(d.strengths()),
            cipher(d.weaknesses()),
            cipher(d.remedialPlan()),
            cipher(d.breakthrough()),
            cipher(d.reason()),
            d.dekId())
        .update();
  }

  @Override
  public void update(
      TenantId tenant, int periodBucket, StoredSelfAssessment d, UUID actor, Instant at) {
    jdbc.sql(
            """
            UPDATE appraisal.self_assessment
               SET form_code = ?, form_version = ?, structured_data = ?::jsonb, claims_json = ?::jsonb,
                   content_enc = ?, strengths_enc = ?, weaknesses_enc = ?, remedial_plan_enc = ?,
                   breakthrough_enc = ?, reason_enc = ?, dek_id = ?, content_hash = NULL,
                   submitted_at = NULL, sign_request_id = NULL, updated_at = ?, updated_by = ?,
                   row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND signed_at IS NULL
            """)
        .params(
            d.formCode(),
            d.formVersion(),
            d.structuredJson(),
            d.claimsJson(),
            cipher(d.content()),
            cipher(d.strengths()),
            cipher(d.weaknesses()),
            cipher(d.remedialPlan()),
            cipher(d.breakthrough()),
            cipher(d.reason()),
            d.dekId(),
            Timestamp.from(at),
            actor,
            periodBucket,
            tenant.value(),
            d.id())
        .update();
  }

  @Override
  public void replaceCriteria(
      TenantId tenant,
      ProfileRow profile,
      List<SelfAssessmentForm.Criterion> criteria,
      BigDecimal ratio,
      CriteriaLevel level,
      UUID actor,
      Instant at) {
    jdbc.sql(
            "DELETE FROM appraisal.general_criteria_result WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? AND stage = 'TU_DANH_GIA'")
        .params(profile.periodBucket(), tenant.value(), profile.id())
        .update();
    Timestamp ts = Timestamp.from(at);
    for (SelfAssessmentForm.Criterion c : criteria) {
      jdbc.sql(
              """
              INSERT INTO appraisal.general_criteria_result (id, tenant_id, created_at, created_by,
                  updated_at, updated_by, period_id, period_bucket, profile_id, stage, criteria_code,
                  compliance_ratio, level, met)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TU_DANH_GIA', ?, ?, ?, ?)
              """)
          .params(
              UUID.randomUUID(),
              tenant.value(),
              ts,
              actor,
              ts,
              actor,
              profile.periodId(),
              profile.periodBucket(),
              profile.id(),
              c.code(),
              ratio,
              level.name(),
              c.met())
          .update();
    }
  }

  @Override
  public Optional<Long> touchProfile(TenantId tenant, ProfileRow profile, UUID actor, Instant at) {
    int n =
        jdbc.sql(
                """
                UPDATE appraisal.appraisal_profile
                   SET updated_at = ?, updated_by = ?, row_version = row_version + 1
                 WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
                """)
            .params(
                Timestamp.from(at),
                actor,
                profile.periodBucket(),
                tenant.value(),
                profile.id(),
                profile.rowVersion())
            .update();
    return n == 1 ? Optional.of(profile.rowVersion() + 1) : Optional.empty();
  }

  @Override
  public void flagMissing(TenantId tenant, ProfileRow profile) {
    jdbc.sql(
            "UPDATE appraisal.appraisal_profile SET is_self_assessment_missing = true"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(profile.periodBucket(), tenant.value(), profile.id())
        .update();
  }

  @Override
  public void markPending(
      TenantId tenant, int periodBucket, UUID selfAssessmentId, byte[] contentHash, Instant at) {
    jdbc.sql(
            """
            UPDATE appraisal.self_assessment
               SET content_hash = ?, submitted_at = ?, sign_request_id = NULL, updated_at = ?,
                   row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ?
            """)
        .params(
            contentHash,
            Timestamp.from(at),
            Timestamp.from(at),
            periodBucket,
            tenant.value(),
            selfAssessmentId)
        .update();
  }

  @Override
  public void attachSignRequest(
      TenantId tenant, int periodBucket, UUID selfAssessmentId, UUID signRequestId, Instant at) {
    jdbc.sql(
            """
            UPDATE appraisal.self_assessment
               SET sign_request_id = ?, updated_at = ?, row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ?
            """)
        .params(signRequestId, Timestamp.from(at), periodBucket, tenant.value(), selfAssessmentId)
        .update();
  }

  @Override
  public void clearPending(TenantId tenant, int periodBucket, UUID selfAssessmentId, Instant at) {
    jdbc.sql(
            """
            UPDATE appraisal.self_assessment
               SET content_hash = NULL, submitted_at = NULL, sign_request_id = NULL, updated_at = ?,
                   row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND signed_at IS NULL
            """)
        .params(Timestamp.from(at), periodBucket, tenant.value(), selfAssessmentId)
        .update();
  }

  @Override
  public void markSigned(
      TenantId tenant, int periodBucket, UUID selfAssessmentId, Instant signedAt, Instant at) {
    jdbc.sql(
            """
            UPDATE appraisal.self_assessment
               SET signed_at = ?, updated_at = ?, row_version = row_version + 1
             WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND signed_at IS NULL
            """)
        .params(
            Timestamp.from(signedAt),
            Timestamp.from(at),
            periodBucket,
            tenant.value(),
            selfAssessmentId)
        .update();
  }

  @Override
  public Optional<SignTarget> findBySignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            "SELECT id, profile_id, period_bucket, signed_at FROM appraisal.self_assessment"
                + " WHERE tenant_id = ? AND sign_request_id = ? LIMIT 1")
        .params(tenant.value(), signRequestId)
        .query(
            (rs, i) ->
                new SignTarget(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getInt(3),
                    instant(rs.getTimestamp(4))))
        .optional();
  }

  private static final String SUMMARY_SQL =
      """
      SELECT p.id, p.period_id, p.period_bucket, p.person_id, p.org_unit_id, p.flow, p.status,
             p.is_head, p.is_leadership, p.current_snapshot_id, s.auto_rating_code,
             p.final_rating_code, p.appraiser_id, p.decider_id, p.return_count,
             p.is_self_assessment_missing, p.submitted_at, p.decided_at, p.locked_at, p.row_version
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.score_snapshot s
          ON s.period_bucket = p.period_bucket AND s.tenant_id = p.tenant_id
         AND s.id = p.current_snapshot_id
      """;

  @Override
  public Optional<ProfileSummary> summary(TenantId tenant, UUID profileId) {
    return jdbc.sql(SUMMARY_SQL + " WHERE p.tenant_id = ? AND p.id = ?")
        .params(tenant.value(), profileId)
        .query((rs, i) -> summary(rs))
        .optional();
  }

  @Override
  public List<ProfileSummary> mine(
      TenantId tenant, UUID personId, UUID periodId, String flow, UUID after, int limit) {
    return jdbc.sql(
            SUMMARY_SQL
                + " WHERE p.tenant_id = ? AND p.person_id = ?"
                + " AND (CAST(? AS uuid) IS NULL OR p.period_id = ?)"
                + " AND (CAST(? AS varchar) IS NULL OR p.flow = ?)"
                + " AND (CAST(? AS uuid) IS NULL OR p.id > ?)"
                + " ORDER BY p.id LIMIT ?")
        .params(tenant.value(), personId, periodId, periodId, flow, flow, after, after, limit)
        .query((rs, i) -> summary(rs))
        .list();
  }

  @Override
  public List<CriteriaRow> criteria(TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            "SELECT stage, criteria_code, compliance_ratio, level, met"
                + " FROM appraisal.general_criteria_result WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? ORDER BY stage, criteria_code")
        .params(periodBucket, tenant.value(), profileId)
        .query(
            (rs, i) ->
                new CriteriaRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getBigDecimal(3),
                    CriteriaLevel.valueOf(rs.getString(4)),
                    rs.getBoolean(5)))
        .list();
  }

  @Override
  public List<TrailRow> trail(TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            "SELECT id, level_kind, rating_code, actor_id, actor_role, is_current, proposed_at"
                + " FROM appraisal.rating_proposal WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? ORDER BY proposed_at, id")
        .params(periodBucket, tenant.value(), profileId)
        .query(
            (rs, i) ->
                new TrailRow(
                    rs.getObject(1, UUID.class),
                    LevelKind.valueOf(rs.getString(2)),
                    RatingLevel.valueOf(rs.getString(3)),
                    rs.getObject(4, UUID.class),
                    rs.getString(5),
                    rs.getBoolean(6),
                    instant(rs.getTimestamp(7))))
        .list();
  }

  static ProfileSummary summary(ResultSet rs) throws SQLException {
    return new ProfileSummary(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        rs.getObject(4, UUID.class),
        rs.getObject(5, UUID.class),
        rs.getString(6),
        AppraisalStatus.valueOf(rs.getString(7)),
        rs.getBoolean(8),
        rs.getBoolean(9),
        rs.getObject(10, UUID.class),
        rating(rs.getString(11)),
        rating(rs.getString(12)),
        rs.getObject(13, UUID.class),
        rs.getObject(14, UUID.class),
        rs.getInt(15),
        rs.getBoolean(16),
        instant(rs.getTimestamp(17)),
        instant(rs.getTimestamp(18)),
        instant(rs.getTimestamp(19)),
        rs.getLong(20));
  }

  private static RatingLevel rating(String code) {
    return code == null ? null : RatingLevel.valueOf(code);
  }

  private static StoredSelfAssessment stored(ResultSet rs) throws SQLException {
    UUID dek = rs.getObject(14, UUID.class);
    return new StoredSelfAssessment(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        sealed(rs.getBytes(8), dek),
        sealed(rs.getBytes(9), dek),
        sealed(rs.getBytes(10), dek),
        sealed(rs.getBytes(11), dek),
        sealed(rs.getBytes(12), dek),
        sealed(rs.getBytes(13), dek),
        dek,
        rs.getBytes(15),
        instant(rs.getTimestamp(16)),
        rs.getObject(17, UUID.class),
        instant(rs.getTimestamp(18)),
        instant(rs.getTimestamp(19)));
  }

  private static SealedText sealed(byte[] cipher, UUID dek) {
    return cipher == null ? null : new SealedText(cipher, dek);
  }

  private static byte[] cipher(SealedText s) {
    return s == null ? null : s.cipher();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
