package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hàng đợi thẩm định và dòng mức thẩm định có ký trên {@code appraisalDataSource} (CORE-04-05). Họ
 * tên, tên đơn vị và cây đơn vị đọc từ {@code org_replica} (mọi {@code svc_} của mission-core được
 * đọc, TL-03). Luồng {@code NHA_NUOC} dùng cây {@code CHUYEN_MON}, luồng {@code DANG} dùng cây
 * {@code DANG} (suy luận).
 */
public class JdbcReviewRepository implements ReviewRepository {

  private static final String QUEUE_SQL =
      """
      SELECT p.id, p.period_id, p.period_bucket, p.person_id, p.org_unit_id, p.flow, p.status,
             p.is_head, p.is_leadership, p.current_snapshot_id, s.auto_rating_code,
             p.final_rating_code, p.appraiser_id, p.decider_id, p.return_count,
             p.is_self_assessment_missing, p.submitted_at, p.decided_at, p.locked_at, p.row_version,
             pv.full_name, u.name
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.score_snapshot s
          ON s.period_bucket = p.period_bucket AND s.tenant_id = p.tenant_id
         AND s.id = p.current_snapshot_id
        LEFT JOIN org_replica.person_v pv ON pv.tenant_id = p.tenant_id AND pv.id = p.person_id
        LEFT JOIN LATERAL (
              SELECT ou.name FROM org_replica.org_unit_v ou
               WHERE ou.tenant_id = p.tenant_id AND ou.id = p.org_unit_id
               ORDER BY ou.valid_from DESC LIMIT 1) u ON true
       WHERE p.tenant_id = ?
      """;

  private static final String SIGN_SQL =
      "SELECT id, profile_id, period_bucket, rating_code, actor_id, is_current, reason_enc,"
          + " comment_enc, dek_id, sign_request_id, signed_at FROM appraisal.rating_proposal";

  private final JdbcClient jdbc;

  public JdbcReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<QueueRow> queue(
      TenantId tenant, QueueFilter filter, Scope scope, UUID after, int limit) {
    StringBuilder sql = new StringBuilder(QUEUE_SQL);
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    if (filter.periodId() != null) {
      sql.append(" AND p.period_id = ?");
      params.add(filter.periodId());
    }
    if (filter.flow() != null) {
      sql.append(" AND p.flow = ?");
      params.add(filter.flow());
    }
    if (filter.unitId() != null) {
      if (filter.includeSubunits()) {
        sql.append(
            " AND p.org_unit_id IN (SELECT uc.descendant_id FROM org_replica.unit_closure_v uc"
                + " WHERE uc.tenant_id = ? AND uc.tree = ? AND uc.ancestor_id = ?)");
        params.add(tenant.value());
        params.add("DANG".equals(filter.flow()) ? "DANG" : "CHUYEN_MON");
        params.add(filter.unitId());
      } else {
        sql.append(" AND p.org_unit_id = ?");
        params.add(filter.unitId());
      }
    }
    if (!filter.statuses().isEmpty()) {
      sql.append(" AND p.status = ANY(?::varchar[])");
      params.add(filter.statuses().toArray(new String[0]));
    }
    if (filter.subjectGroupId() != null) {
      sql.append(" AND p.subject_group_id = ?");
      params.add(filter.subjectGroupId());
    }
    if (filter.q() != null && !filter.q().isBlank()) {
      sql.append(" AND pv.full_name ILIKE ?");
      params.add("%" + escapeLike(filter.q().trim()) + "%");
    }
    if (after != null) {
      sql.append(" AND p.id > ?");
      params.add(after);
    }
    sql.append(" AND (").append(scope.sql()).append(')');
    params.addAll(scope.params());
    sql.append(" ORDER BY p.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) ->
                new QueueRow(
                    JdbcSelfAssessmentRepository.summary(rs), rs.getString(21), rs.getString(22)))
        .list();
  }

  @Override
  public Map<UUID, Map<LevelKind, RatingLevel>> currentLevels(
      TenantId tenant, Set<UUID> profileIds) {
    Map<UUID, Map<LevelKind, RatingLevel>> out = new HashMap<>();
    jdbc.sql(
            "SELECT profile_id, level_kind, rating_code FROM appraisal.rating_proposal"
                + " WHERE tenant_id = ? AND profile_id = ANY(?::uuid[]) AND is_current")
        .params(tenant.value(), profileIds.toArray(new UUID[0]))
        .query(
            (ResultSet rs) -> {
              out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new EnumMap<>(LevelKind.class))
                  .put(LevelKind.valueOf(rs.getString(2)), RatingLevel.valueOf(rs.getString(3)));
            });
    return out;
  }

  @Override
  public void replaceAppraisalCriteria(
      TenantId tenant,
      ProfileRow profile,
      List<SelfAssessmentForm.Criterion> criteria,
      BigDecimal ratio,
      CriteriaLevel level,
      UUID actor,
      Instant at) {
    jdbc.sql(
            "DELETE FROM appraisal.general_criteria_result WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? AND stage = 'THAM_DINH'")
        .params(profile.periodBucket(), tenant.value(), profile.id())
        .update();
    Timestamp ts = Timestamp.from(at);
    for (SelfAssessmentForm.Criterion c : criteria) {
      jdbc.sql(
              """
              INSERT INTO appraisal.general_criteria_result (id, tenant_id, created_at, created_by,
                  updated_at, updated_by, period_id, period_bucket, profile_id, stage, criteria_code,
                  compliance_ratio, level, met)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'THAM_DINH', ?, ?, ?, ?)
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
  public List<SelfAssessmentForm.Criterion> selfCriteria(
      TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            "SELECT criteria_code, met FROM appraisal.general_criteria_result WHERE period_bucket = ?"
                + " AND tenant_id = ? AND profile_id = ? AND stage = 'TU_DANH_GIA'"
                + " ORDER BY criteria_code")
        .params(periodBucket, tenant.value(), profileId)
        .query((rs, i) -> new SelfAssessmentForm.Criterion(rs.getString(1), rs.getBoolean(2)))
        .list();
  }

  @Override
  public Optional<AppraisalSign> currentAppraisal(
      TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            SIGN_SQL
                + " WHERE period_bucket = ? AND tenant_id = ? AND profile_id = ?"
                + " AND level_kind = 'THAM_DINH' AND is_current")
        .params(periodBucket, tenant.value(), profileId)
        .query((rs, i) -> sign(rs))
        .optional();
  }

  @Override
  public Optional<AppraisalSign> appraisalBySignRequest(TenantId tenant, UUID signRequestId) {
    return jdbc.sql(
            SIGN_SQL
                + " WHERE tenant_id = ? AND sign_request_id = ? AND level_kind = 'THAM_DINH'"
                + " ORDER BY proposed_at DESC LIMIT 1")
        .params(tenant.value(), signRequestId)
        .query((rs, i) -> sign(rs))
        .optional();
  }

  @Override
  public void attachSignRequest(
      TenantId tenant, int periodBucket, UUID proposalId, UUID signRequestId, Instant at) {
    jdbc.sql(
            "UPDATE appraisal.rating_proposal SET sign_request_id = ?, updated_at = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ?")
        .params(signRequestId, Timestamp.from(at), periodBucket, tenant.value(), proposalId)
        .update();
  }

  @Override
  public void markSigned(
      TenantId tenant, int periodBucket, UUID proposalId, Instant signedAt, Instant at) {
    jdbc.sql(
            "UPDATE appraisal.rating_proposal SET signed_at = ?, updated_at = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ? AND signed_at IS NULL")
        .params(
            Timestamp.from(signedAt), Timestamp.from(at), periodBucket, tenant.value(), proposalId)
        .update();
  }

  private static AppraisalSign sign(ResultSet rs) throws SQLException {
    UUID dek = rs.getObject(9, UUID.class);
    byte[] reason = rs.getBytes(7);
    byte[] comment = rs.getBytes(8);
    Timestamp signed = rs.getTimestamp(11);
    return new AppraisalSign(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        RatingLevel.valueOf(rs.getString(4)),
        rs.getObject(5, UUID.class),
        rs.getBoolean(6),
        reason == null ? null : new SealedText(reason, dek),
        comment == null ? null : new SealedText(comment, dek),
        rs.getObject(10, UUID.class),
        signed == null ? null : signed.toInstant());
  }

  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
