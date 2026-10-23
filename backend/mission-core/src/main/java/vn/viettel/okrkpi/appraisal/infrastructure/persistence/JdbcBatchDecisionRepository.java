package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lô quyết định trên {@code appraisalDataSource} (CORE-04-07). Số quyết định duy nhất trong năm
 * kiểm bằng {@code period_bucket / 10} (khoá phân vùng {@code YYYYQ}); ràng buộc {@code
 * uq_batch_decision__decision_no} chặn trùng trong kỳ khi hai lệnh chạy song song. Cây đơn vị của
 * quy tắc người đứng đầu: luồng {@code DANG} dùng cây {@code DANG}, luồng {@code NHA_NUOC} dùng cây
 * {@code CHUYEN_MON} (suy luận như hàng đợi thẩm định).
 */
public class JdbcBatchDecisionRepository implements BatchDecisionRepository {

  private static final String SELECT =
      """
      SELECT b.id, b.period_id, b.period_bucket, b.flow, b.decision_no, b.decision_date,
             b.decider_id, pv.full_name, b.profile_count, b.profiles_hash, b.sign_request_id,
             b.status, b.row_version, b.updated_at
        FROM appraisal.batch_decision b
        LEFT JOIN org_replica.person_v pv ON pv.tenant_id = b.tenant_id AND pv.id = b.decider_id
      """;

  private final JdbcClient jdbc;

  public JdbcBatchDecisionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<BatchRow> findByNumber(TenantId tenant, int year, String decisionNo) {
    return jdbc.sql(
            SELECT
                + " WHERE b.tenant_id = ? AND b.period_bucket BETWEEN ? AND ? AND b.decision_no = ?"
                + " ORDER BY b.created_at DESC LIMIT 1")
        .params(tenant.value(), year * 10, year * 10 + 9, decisionNo)
        .query(JdbcBatchDecisionRepository::row)
        .optional();
  }

  @Override
  public Optional<BatchRow> find(TenantId tenant, UUID id, boolean lock) {
    return jdbc.sql(
            SELECT + " WHERE b.tenant_id = ? AND b.id = ?" + (lock ? " FOR UPDATE OF b" : ""))
        .params(tenant.value(), id)
        .query(JdbcBatchDecisionRepository::row)
        .optional();
  }

  @Override
  public List<BatchRow> page(
      TenantId tenant,
      UUID decider,
      UUID periodId,
      String flow,
      Set<String> statuses,
      UUID after,
      int limit) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE b.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    sql.append(" AND b.decider_id = ?");
    params.add(decider);
    if (periodId != null) {
      sql.append(" AND b.period_id = ?");
      params.add(periodId);
    }
    if (flow != null) {
      sql.append(" AND b.flow = ?");
      params.add(flow);
    }
    if (statuses != null && !statuses.isEmpty()) {
      sql.append(" AND b.status = ANY(?::varchar[])");
      params.add(statuses.toArray(new String[0]));
    }
    if (after != null) {
      sql.append(" AND b.id < ?");
      params.add(after);
    }
    sql.append(" ORDER BY b.id DESC LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(JdbcBatchDecisionRepository::row).list();
  }

  @Override
  public void insert(TenantId tenant, NewBatch b) {
    Timestamp at = Timestamp.from(b.at());
    jdbc.sql(
            "INSERT INTO appraisal.batch_decision (id, tenant_id, created_at, created_by,"
                + " updated_at, updated_by, period_id, period_bucket, decision_no, decision_date,"
                + " decider_id, flow, profile_count, profiles_hash, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NHAP')")
        .params(
            b.id(),
            tenant.value(),
            at,
            b.deciderId(),
            at,
            b.deciderId(),
            b.periodId(),
            b.periodBucket(),
            b.decisionNo(),
            b.decisionDate(),
            b.deciderId(),
            b.flow(),
            b.profileCount(),
            b.profilesHash())
        .update();
  }

  @Override
  public void attachSignRequest(
      TenantId tenant, int bucket, UUID id, UUID signRequestId, UUID actor, Instant at) {
    Timestamp ts = Timestamp.from(at);
    jdbc.sql(
            "UPDATE appraisal.batch_decision SET sign_request_id = ?, status = 'CHO_KY',"
                + " updated_at = ?, updated_by = ?, row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND status = 'NHAP'")
        .params(signRequestId, ts, actor, bucket, tenant.value(), id)
        .update();
    jdbc.sql(
            "UPDATE appraisal.rating_proposal SET sign_request_id = ?, updated_at = ?,"
                + " updated_by = ?, row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND batch_decision_id = ?"
                + " AND is_current AND sign_request_id IS NULL")
        .params(signRequestId, ts, actor, bucket, tenant.value(), id)
        .update();
  }

  @Override
  public void setStatus(
      TenantId tenant, int bucket, UUID id, String status, UUID actor, Instant at) {
    jdbc.sql(
            "UPDATE appraisal.batch_decision SET status = ?, updated_at = ?, updated_by = ?,"
                + " row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(status, Timestamp.from(at), actor, bucket, tenant.value(), id)
        .update();
  }

  @Override
  public List<UUID> supersedeUnsigned(
      TenantId tenant, int bucket, UUID id, UUID actor, Instant at) {
    return jdbc.sql(
            "UPDATE appraisal.rating_proposal SET is_current = false, updated_at = ?,"
                + " updated_by = ?, row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND batch_decision_id = ?"
                + " AND is_current AND signed_at IS NULL RETURNING profile_id")
        .params(Timestamp.from(at), actor, bucket, tenant.value(), id)
        .query(UUID.class)
        .list();
  }

  @Override
  public void touchProfiles(
      TenantId tenant, int bucket, Collection<UUID> profileIds, UUID actor, Instant at) {
    if (profileIds.isEmpty()) {
      return;
    }
    jdbc.sql(
            "UPDATE appraisal.appraisal_profile SET updated_at = ?, updated_by = ?,"
                + " row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ANY(?::uuid[])")
        .params(Timestamp.from(at), actor, bucket, tenant.value(), profileIds.toArray(new UUID[0]))
        .update();
  }

  @Override
  public Map<UUID, ProfileFacts> facts(TenantId tenant, int bucket, Collection<UUID> profileIds) {
    Map<UUID, ProfileFacts> out = new HashMap<>();
    jdbc.sql(
            """
            SELECT p.id, p.org_unit_id, p.is_head,
                   (SELECT b.id FROM appraisal.rating_proposal r
                      JOIN appraisal.batch_decision b
                        ON b.period_bucket = r.period_bucket AND b.tenant_id = r.tenant_id
                       AND b.id = r.batch_decision_id
                     WHERE r.period_bucket = p.period_bucket AND r.tenant_id = p.tenant_id
                       AND r.profile_id = p.id AND r.level_kind = 'QUYET_DINH' AND r.is_current
                       AND b.status IN ('NHAP', 'CHO_KY')
                     LIMIT 1)
              FROM appraisal.appraisal_profile p
             WHERE p.period_bucket = ? AND p.tenant_id = ? AND p.id = ANY(?::uuid[])
            """)
        .params(bucket, tenant.value(), profileIds.toArray(new UUID[0]))
        .query(
            (rs, i) ->
                new ProfileFacts(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getBoolean(3),
                    rs.getObject(4, UUID.class)))
        .list()
        .forEach(f -> out.put(f.profileId(), f));
    return out;
  }

  @Override
  public Set<UUID> outOfScope(
      TenantId tenant, int bucket, Collection<UUID> profileIds, Scope scope) {
    List<Object> params = new ArrayList<>();
    params.add(bucket);
    params.add(tenant.value());
    params.add(profileIds.toArray(new UUID[0]));
    params.addAll(scope.params());
    return new HashSet<>(
        jdbc.sql(
                "SELECT p.id FROM appraisal.appraisal_profile p WHERE p.period_bucket = ?"
                    + " AND p.tenant_id = ? AND p.id = ANY(?::uuid[]) AND NOT coalesce(("
                    + scope.sql()
                    + "), false)")
            .params(params)
            .query(UUID.class)
            .list());
  }

  @Override
  public int undecidedSubordinates(
      TenantId tenant,
      int bucket,
      UUID periodId,
      String flow,
      UUID orgUnitId,
      Collection<UUID> inBatch) {
    Integer n =
        jdbc.sql(
                """
                SELECT count(*) FROM appraisal.appraisal_profile p
                 WHERE p.period_bucket = ? AND p.tenant_id = ? AND p.period_id = ? AND p.flow = ?
                   AND p.org_unit_id IN (SELECT uc.descendant_id FROM org_replica.unit_closure_v uc
                                          WHERE uc.tenant_id = ? AND uc.tree = ?
                                            AND uc.ancestor_id = ? AND uc.depth >= 1)
                   AND NOT (p.id = ANY(?::uuid[]))
                   AND NOT EXISTS (SELECT 1 FROM appraisal.rating_proposal r
                                    WHERE r.period_bucket = p.period_bucket
                                      AND r.tenant_id = p.tenant_id AND r.profile_id = p.id
                                      AND r.level_kind = 'QUYET_DINH' AND r.is_current)
                """)
            .params(
                bucket,
                tenant.value(),
                periodId,
                flow,
                tenant.value(),
                "DANG".equals(flow) ? "DANG" : "CHUYEN_MON",
                orgUnitId,
                inBatch.toArray(new UUID[0]))
            .query(Integer.class)
            .single();
    return n == null ? 0 : n;
  }

  @Override
  public List<BatchRating> ratings(TenantId tenant, int bucket, UUID id) {
    return jdbc.sql(
            "SELECT id, profile_id, rating_code, signed_at FROM appraisal.rating_proposal"
                + " WHERE period_bucket = ? AND tenant_id = ? AND batch_decision_id = ?"
                + " AND is_current ORDER BY profile_id")
        .params(bucket, tenant.value(), id)
        .query(
            (rs, i) -> {
              Timestamp signed = rs.getTimestamp(4);
              return new BatchRating(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getString(3),
                  signed == null ? null : signed.toInstant());
            })
        .list();
  }

  @Override
  public void markSigned(
      TenantId tenant,
      int bucket,
      UUID proposalId,
      UUID signRequestId,
      Instant signedAt,
      Instant at) {
    jdbc.sql(
            "UPDATE appraisal.rating_proposal SET sign_request_id = ?, signed_at = ?,"
                + " updated_at = ?, row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND signed_at IS NULL")
        .params(
            signRequestId,
            Timestamp.from(signedAt),
            Timestamp.from(at),
            bucket,
            tenant.value(),
            proposalId)
        .update();
  }

  @Override
  public List<LockCandidate> signedProfiles(
      TenantId tenant, Instant decidedBefore, UUID after, int limit) {
    return jdbc.sql(
            "SELECT id, decided_at, policy_pack_version_id FROM appraisal.appraisal_profile"
                + " WHERE tenant_id = ? AND status = 'DA_KY_SO' AND decided_at < ? AND id > ?"
                + " ORDER BY id LIMIT ?")
        .params(
            tenant.value(),
            Timestamp.from(decidedBefore),
            after == null ? new UUID(0L, 0L) : after,
            limit)
        .query(
            (rs, i) ->
                new LockCandidate(
                    rs.getObject(1, UUID.class),
                    rs.getTimestamp(2).toInstant(),
                    rs.getObject(3, UUID.class)))
        .list();
  }

  @Override
  public int signedCount(TenantId tenant, int bucket, UUID id) {
    Integer n =
        jdbc.sql(
                "SELECT count(*) FROM appraisal.rating_proposal WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND batch_decision_id = ? AND signed_at IS NOT NULL")
            .params(bucket, tenant.value(), id)
            .query(Integer.class)
            .single();
    return n == null ? 0 : n;
  }

  private static BatchRow row(ResultSet rs, int i) throws SQLException {
    return new BatchRow(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getInt(3),
        rs.getString(4),
        rs.getString(5),
        rs.getObject(6, java.time.LocalDate.class),
        rs.getObject(7, UUID.class),
        rs.getString(8),
        rs.getInt(9),
        rs.getBytes(10),
        rs.getObject(11, UUID.class),
        rs.getString(12),
        rs.getLong(13),
        rs.getTimestamp(14).toInstant());
  }

  @Override
  public Set<UUID> withOpenQuarterComplaint(
      TenantId tenant, int bucket, Collection<UUID> profileIds) {
    return new HashSet<>(
        jdbc.sql(
                """
                SELECT a.id FROM appraisal.appraisal_profile a
                 WHERE a.period_bucket = ? AND a.tenant_id = ? AND a.id = ANY(?::uuid[])
                   AND EXISTS (SELECT 1 FROM appraisal.appraisal_profile q
                                WHERE q.period_bucket = ANY(?::int[]) AND q.tenant_id = a.tenant_id
                                  AND q.person_id = a.person_id AND q.flow = a.flow
                                  AND q.status = 'KHIEU_NAI')
                """)
            .params(
                bucket,
                tenant.value(),
                profileIds.toArray(UUID[]::new),
                new Integer[] {bucket + 1, bucket + 2, bucket + 3, bucket + 4})
            .query(UUID.class)
            .list());
  }
}
