package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.setup.port.ProfileSetupRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lập hồ sơ quý và ghi ảnh chụp điểm (CORE-04-02). Hồ sơ lập bằng {@code INSERT … SELECT} theo lô
 * {@value #CHUNK} người, duyệt người theo con trỏ trên chỉ mục {@code
 * ix_assignment_v__person_valid_to} để mỗi câu lệnh ngắn (vai trò ứng dụng ở cell pool có {@code
 * statement_timeout} 5 giây). Phân công đọc theo trục thời gian hiệu lực tại ngày khoá kỳ; bản sao
 * không giữ trục thời gian ghi nhận nên {@code org_asof_recorded_at} chỉ ghi lại thời điểm khoá
 * (wiki trang 14 mục 59).
 */
public class JdbcProfileSetupRepository implements ProfileSetupRepository {

  static final int CHUNK = 5000;

  /** Tình trạng công tác thuộc diện đánh giá; tạm nghỉ do trường hợp đặc thù quyết định. */
  static final String[] EMPLOYED = {"DANG_CONG_TAC", "TAM_NGHI"};

  private static final String CREATE_SQL =
      """
      WITH x AS (
        SELECT DISTINCT ON (a.person_id) a.person_id, a.org_unit_id, a.id, a.job_title_id,
               a.is_head, ou.subject_group_id
          FROM org_replica.assignment_v a
          JOIN org_replica.person_v pv ON pv.tenant_id = a.tenant_id AND pv.id = a.person_id
          LEFT JOIN LATERAL (
                SELECT o.subject_group_id FROM org_replica.org_unit_v o
                 WHERE o.tenant_id = a.tenant_id AND o.id = a.org_unit_id
                   AND o.valid_from <= ? AND o.valid_to >= ?
                 ORDER BY o.valid_from DESC LIMIT 1) ou ON true
         WHERE a.tenant_id = ? AND a.valid_from <= ? AND a.valid_to >= ?
           AND a.person_id > ? AND a.person_id <> ALL(?::uuid[])
           AND pv.employment_status = ANY(?::varchar[]) AND (? OR pv.is_party_member)
         ORDER BY a.person_id, (a.branch = 'CHINH') DESC, (a.kind = 'CHINH') DESC,
                  a.valid_from DESC, a.id
         LIMIT ?),
      ins AS (
        INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,
               period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,
               subject_group_id, is_head, is_leadership, policy_pack_version_id,
               org_asof_recorded_at, org_asof_valid_date)
        SELECT gen_random_uuid(), ?, ?, ?, ?, x.person_id, ?, x.org_unit_id, x.id, x.job_title_id,
               x.subject_group_id, x.is_head, x.is_head, m.version_id, ?, ?
          FROM x JOIN unnest(?::uuid[], ?::uuid[]) AS m(unit_id, version_id)
            ON m.unit_id = x.org_unit_id
        ON CONFLICT DO NOTHING
        RETURNING 1)
      SELECT (SELECT x.person_id FROM x ORDER BY x.person_id DESC LIMIT 1),
             (SELECT count(*) FROM ins)
      """;

  /** Nhỏ nhất theo thứ tự byte không dấu của PostgreSQL. */
  private static final UUID MIN = new UUID(0L, 0L);

  private final JdbcClient jdbc;

  public JdbcProfileSetupRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<UnitGroup> unitGroups(TenantId tenant, LocalDate validDate) {
    Date d = Date.valueOf(validDate);
    return jdbc.sql(
            """
            SELECT DISTINCT a.org_unit_id, ou.subject_group_id
              FROM org_replica.assignment_v a
              LEFT JOIN LATERAL (
                    SELECT o.subject_group_id FROM org_replica.org_unit_v o
                     WHERE o.tenant_id = a.tenant_id AND o.id = a.org_unit_id
                       AND o.valid_from <= ? AND o.valid_to >= ?
                     ORDER BY o.valid_from DESC LIMIT 1) ou ON true
             WHERE a.tenant_id = ? AND a.valid_from <= ? AND a.valid_to >= ?
            """)
        .params(d, d, tenant.value(), d, d)
        .query((rs, i) -> new UnitGroup(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  @Override
  public int createProfiles(
      TenantId tenant,
      PeriodLock lock,
      String flow,
      Map<UUID, UUID> packs,
      Collection<UUID> excluded,
      UUID actor) {
    if (packs.isEmpty()) {
      return 0;
    }
    Date d = Date.valueOf(lock.validDate());
    UUID[] units = packs.keySet().toArray(UUID[]::new);
    UUID[] versions = packs.values().toArray(UUID[]::new);
    UUID[] skip = excluded.toArray(UUID[]::new);
    boolean everyone = !"DANG".equals(flow);
    UUID after = MIN;
    int created = 0;
    while (after != null) {
      List<Object> params = new ArrayList<>();
      params.addAll(List.of(d, d, tenant.value(), d, d, after, skip, EMPLOYED, everyone, CHUNK));
      params.addAll(
          List.of(
              actor,
              actor,
              lock.periodId(),
              lock.periodBucket(),
              flow,
              Timestamp.from(lock.lockedAt()),
              d,
              units,
              versions));
      Object[] r =
          jdbc.sql(CREATE_SQL)
              .params(params)
              .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getLong(2)})
              .single();
      after = (UUID) r[0];
      created += (int) (long) (Long) r[1];
    }
    return created;
  }

  @Override
  public Optional<SnapshotHead> currentSnapshot(TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            "SELECT id, score_result_id, computed_at FROM appraisal.score_snapshot"
                + " WHERE period_bucket = ? AND tenant_id = ? AND profile_id = ?"
                + " AND superseded_by_id IS NULL")
        .params(periodBucket, tenant.value(), profileId)
        .query(
            (rs, i) ->
                new SnapshotHead(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getTimestamp(3).toInstant()))
        .optional();
  }

  @Override
  public boolean hasResult(TenantId tenant, int periodBucket, UUID scoreResultId) {
    return jdbc.sql(
                "SELECT count(*) FROM appraisal.score_snapshot WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND score_result_id = ?")
            .params(periodBucket, tenant.value(), scoreResultId)
            .query(Long.class)
            .single()
        > 0;
  }

  @Override
  public void insertSnapshot(TenantId tenant, NewSnapshot s, UUID previousId, UUID actor) {
    Timestamp now = Timestamp.from(Instant.now());
    if (previousId != null) {
      jdbc.sql(
              "UPDATE appraisal.score_snapshot SET superseded_by_id = ?, updated_at = ?,"
                  + " updated_by = ?, row_version = row_version + 1 WHERE period_bucket = ?"
                  + " AND tenant_id = ? AND id = ? AND superseded_by_id IS NULL")
          .params(s.id(), now, actor, s.periodBucket(), tenant.value(), previousId)
          .update();
    }
    jdbc.sql(
            """
            INSERT INTO appraisal.score_snapshot (id, tenant_id, created_by, updated_by, period_id,
                period_bucket, profile_id, flow, snapshot_kind, score_result_id,
                policy_pack_version_id, policy_content_hash, policy_version, input_hash, total_score,
                auto_rating_code, rule_checks, group_scores_json, computed_at, snapshot_hash,
                detail_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, '{}'::jsonb, ?, ?,
                ?::jsonb)
            """)
        .params(
            s.id(),
            tenant.value(),
            actor,
            actor,
            s.periodId(),
            s.periodBucket(),
            s.profileId(),
            s.flow(),
            s.kind(),
            s.scoreResultId(),
            s.policyPackVersionId(),
            s.policyContentHash(),
            s.policyVersion(),
            s.inputHash(),
            s.totalScore(),
            s.autoRatingCode(),
            Timestamp.from(s.computedAt()),
            s.snapshotHash(),
            s.detailJson())
        .update();
  }

  @Override
  public void pointCurrent(
      TenantId tenant, int periodBucket, UUID profileId, UUID snapshotId, UUID actor, Instant at) {
    jdbc.sql(
            "UPDATE appraisal.appraisal_profile SET current_snapshot_id = ?, updated_at = ?,"
                + " updated_by = ? WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(snapshotId, Timestamp.from(at), actor, periodBucket, tenant.value(), profileId)
        .update();
  }
}
