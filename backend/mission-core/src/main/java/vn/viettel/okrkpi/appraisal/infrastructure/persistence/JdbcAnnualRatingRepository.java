package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ năm và {@code appraisal.annual_rating} (CORE-04-10). Lập theo lô {@value #CHUNK} người
 * duyệt hồ sơ quý IV theo con trỏ {@code person_id}, một câu lệnh vừa lập hồ sơ năm vừa lập dòng
 * {@code annual_rating}. Mức các quý lấy từ {@code final_rating_code} của hồ sơ quý (mức đã quyết
 * định), quý chưa quyết định không có trong {@code quarter_ratings}.
 */
public class JdbcAnnualRatingRepository implements AnnualRatingRepository {

  static final int CHUNK = 5000;

  private static final String CREATE_SQL =
      """
      WITH q4 AS (
        SELECT p.person_id, p.org_unit_id, p.assignment_id, p.job_title_id, p.subject_group_id,
               p.is_head, p.is_leadership, p.policy_pack_version_id, p.org_asof_recorded_at,
               p.org_asof_valid_date
          FROM appraisal.appraisal_profile p
         WHERE p.period_bucket = ? AND p.tenant_id = ? AND p.flow = ? AND p.person_id > ?
         ORDER BY p.person_id
         LIMIT ?),
      r AS (
        SELECT q.person_id, q.period_bucket, q.final_rating_code,
               lag(q.final_rating_code) OVER w AS prev_code,
               lag(q.period_bucket) OVER w AS prev_bucket
          FROM appraisal.appraisal_profile q JOIN q4 ON q4.person_id = q.person_id
         WHERE q.period_bucket = ANY(?::int[]) AND q.tenant_id = ? AND q.flow = ?
        WINDOW w AS (PARTITION BY q.person_id ORDER BY q.period_bucket)),
      qs AS (
        SELECT person_id,
               coalesce(array_agg(final_rating_code ORDER BY period_bucket)
                        FILTER (WHERE final_rating_code IS NOT NULL), '{}') AS ratings,
               count(*) FILTER (WHERE final_rating_code IS NOT NULL OR period_bucket = ?) AS ready,
               coalesce(bool_or(final_rating_code = 'KHTNV'), false) AS khtnv,
               coalesce(bool_or(final_rating_code = 'KHTNV' AND prev_code = 'KHTNV'
                                AND prev_bucket = period_bucket - 1), false) AS consecutive
          FROM r GROUP BY person_id),
      pick AS (
        SELECT q4.*, qs.ratings, qs.khtnv, qs.consecutive,
               coalesce(m.version_id, q4.policy_pack_version_id) AS version_id
          FROM q4 JOIN qs ON qs.person_id = q4.person_id
          LEFT JOIN unnest(?::uuid[], ?::uuid[]) AS m(unit_id, version_id)
            ON m.unit_id = q4.org_unit_id
         WHERE qs.ready = 4 OR (q4.person_id = ANY(?::uuid[]) AND qs.ready >= 1)),
      ins AS (
        INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,
               period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,
               subject_group_id, is_head, is_leadership, policy_pack_version_id,
               org_asof_recorded_at, org_asof_valid_date)
        SELECT gen_random_uuid(), ?, ?, ?, ?, person_id, ?, org_unit_id, assignment_id,
               job_title_id, subject_group_id, is_head, is_leadership, version_id,
               org_asof_recorded_at, org_asof_valid_date
          FROM pick
        ON CONFLICT DO NOTHING
        RETURNING id, person_id),
      ar AS (
        INSERT INTO appraisal.annual_rating (id, created_by, updated_by, period_id, period_bucket,
               person_id, flow, profile_id, quarter_ratings, has_khtnv_quarter, consecutive_khtnv,
               block_reason, reassign_flag)
        SELECT gen_random_uuid(), ?, ?, ?, ?, i.person_id, ?, i.id, p.ratings::varchar(8)[],
               p.khtnv, p.consecutive, CASE WHEN p.khtnv THEN 'CO_QUY_KHTNV' END, p.consecutive
          FROM ins i JOIN pick p ON p.person_id = i.person_id
        ON CONFLICT DO NOTHING
        RETURNING 1)
      SELECT (SELECT q4.person_id FROM q4 ORDER BY q4.person_id DESC LIMIT 1),
             (SELECT count(*) FROM ar)
      """;

  private static final String ROW_SQL =
      """
      SELECT ar.id, ar.profile_id, ar.person_id, pv.full_name, p.org_unit_id, u.name, ar.flow,
             p.status, ar.annual_score, sp.rating_code, ar.system_level, ar.rating_code,
             ar.has_khtnv_quarter, ar.consecutive_khtnv, ar.block_reason, ar.reassign_flag,
             ar.relocation_proposed, ar.row_version, ar.period_id, ar.period_bucket / 10,
             p.appraiser_id, p.decider_id,
             ARRAY(SELECT coalesce(q.final_rating_code, '') FROM generate_series(1, 4) g(n)
                     LEFT JOIN appraisal.appraisal_profile q
                       ON q.period_bucket = ar.period_bucket + g.n AND q.tenant_id = ar.tenant_id
                      AND q.person_id = ar.person_id AND q.flow = ar.flow
                    ORDER BY g.n)
        FROM appraisal.annual_rating ar
        JOIN appraisal.appraisal_profile p
          ON p.period_bucket = ar.period_bucket AND p.tenant_id = ar.tenant_id
         AND p.id = ar.profile_id
        LEFT JOIN appraisal.rating_proposal sp
          ON sp.period_bucket = p.period_bucket AND sp.tenant_id = p.tenant_id
         AND sp.profile_id = p.id AND sp.level_kind = 'TU_DE_XUAT' AND sp.is_current
        LEFT JOIN org_replica.person_v pv ON pv.tenant_id = p.tenant_id AND pv.id = p.person_id
        LEFT JOIN LATERAL (
              SELECT ou.name FROM org_replica.org_unit_v ou
               WHERE ou.tenant_id = p.tenant_id AND ou.id = p.org_unit_id
               ORDER BY ou.valid_from DESC LIMIT 1) u ON true
       WHERE ar.tenant_id = ?
      """;

  /** Nhỏ nhất theo thứ tự byte không dấu của PostgreSQL. */
  private static final UUID MIN = new UUID(0L, 0L);

  private final JdbcClient jdbc;

  public JdbcAnnualRatingRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<UnitGroup> unitGroups(TenantId tenant, int quarterFourBucket, String flow) {
    return jdbc.sql(
            "SELECT DISTINCT org_unit_id, subject_group_id FROM appraisal.appraisal_profile"
                + " WHERE period_bucket = ? AND tenant_id = ? AND flow = ?")
        .params(quarterFourBucket, tenant.value(), flow)
        .query((rs, i) -> new UnitGroup(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  @Override
  public int createAnnual(
      TenantId tenant,
      UUID annualPeriodId,
      int year,
      String flow,
      Map<UUID, UUID> packs,
      Collection<UUID> special,
      UUID actor) {
    int bucket = year * 10;
    Integer[] quarters = {bucket + 1, bucket + 2, bucket + 3, bucket + 4};
    UUID[] units = packs.keySet().toArray(UUID[]::new);
    UUID[] versions = packs.values().toArray(UUID[]::new);
    UUID[] specials = special.toArray(UUID[]::new);
    UUID after = MIN;
    int created = 0;
    while (after != null) {
      List<Object> params = new ArrayList<>();
      params.addAll(List.of(bucket + 4, tenant.value(), flow, after, CHUNK));
      params.addAll(List.of(quarters, tenant.value(), flow, bucket + 4));
      params.addAll(List.of(units, versions, specials));
      params.addAll(List.of(actor, actor, annualPeriodId, bucket, flow));
      params.addAll(List.of(actor, actor, annualPeriodId, bucket, flow));
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

  /**
   * Khoá dòng năm để lập đề xuất; người thẩm định, người quyết định lấy ở hồ sơ năm, chưa có thì ở
   * hồ sơ quý IV cùng người, cùng luồng.
   */
  private static final String LOCK_SQL =
      """
      SELECT ar.id, ar.period_bucket, ar.period_id, ar.profile_id, ar.person_id, p.org_unit_id,
             ar.flow, ar.reassign_flag, ar.relocation_proposed, ar.row_version,
             COALESCE(p.appraiser_id, q.appraiser_id), COALESCE(p.decider_id, q.decider_id)
        FROM appraisal.annual_rating ar
        JOIN appraisal.appraisal_profile p
          ON p.period_bucket = ar.period_bucket AND p.tenant_id = ar.tenant_id
         AND p.id = ar.profile_id
        LEFT JOIN appraisal.appraisal_profile q
          ON q.period_bucket = ar.period_bucket + 4 AND q.tenant_id = ar.tenant_id
         AND q.person_id = ar.person_id AND q.flow = ar.flow
       WHERE ar.tenant_id = ? AND ar.id = ?
         FOR UPDATE OF ar
      """;

  /**
   * Dòng có cờ vừa lập trong giao dịch: {@code created_at} mặc định {@code now()} là thời điểm bắt
   * đầu giao dịch nên trùng {@code now()} của câu đọc. Người nhận là người thẩm định, người quyết
   * định của hồ sơ quý IV cùng người, cùng luồng.
   */
  private static final String FLAGGED_SQL =
      """
      SELECT ar.profile_id, ar.person_id, q.appraiser_id, q.decider_id
        FROM appraisal.annual_rating ar
        LEFT JOIN appraisal.appraisal_profile q
          ON q.period_bucket = ar.period_bucket + 4 AND q.tenant_id = ar.tenant_id
         AND q.person_id = ar.person_id AND q.flow = ar.flow
       WHERE ar.period_bucket = ? AND ar.tenant_id = ? AND ar.flow = ?
         AND ar.reassign_flag AND ar.created_at = now()
       ORDER BY ar.person_id
      """;

  @Override
  public Optional<ProposalTarget> lockForProposal(TenantId tenant, UUID id) {
    return jdbc.sql(LOCK_SQL)
        .params(tenant.value(), id)
        .query(
            (rs, i) ->
                new ProposalTarget(
                    rs.getObject(1, UUID.class),
                    rs.getInt(2),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class),
                    rs.getObject(5, UUID.class),
                    rs.getObject(6, UUID.class),
                    rs.getString(7),
                    rs.getBoolean(8),
                    rs.getBoolean(9),
                    rs.getLong(10),
                    rs.getObject(11, UUID.class),
                    rs.getObject(12, UUID.class)))
        .optional();
  }

  @Override
  public long markRelocationProposed(TenantId tenant, int periodBucket, UUID id, UUID actor) {
    return jdbc.sql(
            "UPDATE appraisal.annual_rating SET relocation_proposed = true,"
                + " row_version = row_version + 1, updated_at = now(), updated_by = ?"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? RETURNING row_version")
        .params(actor, periodBucket, tenant.value(), id)
        .query(Long.class)
        .single();
  }

  @Override
  public List<Flagged> flaggedInThisTransaction(TenantId tenant, int year, String flow) {
    return jdbc.sql(FLAGGED_SQL)
        .params(year * 10, tenant.value(), flow)
        .query(
            (rs, i) -> {
              List<UUID> to = new ArrayList<>();
              for (int c = 3; c <= 4; c++) {
                UUID who = rs.getObject(c, UUID.class);
                if (who != null && !to.contains(who)) {
                  to.add(who);
                }
              }
              return new Flagged(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), to);
            })
        .list();
  }

  @Override
  public Optional<UUID> annualProfileOf(TenantId tenant, int year, UUID personId, String flow) {
    return jdbc.sql(
            "SELECT profile_id FROM appraisal.annual_rating WHERE period_bucket = ?"
                + " AND tenant_id = ? AND person_id = ? AND flow = ?")
        .params(year * 10, tenant.value(), personId, flow)
        .query(UUID.class)
        .optional();
  }

  @Override
  public List<QuarterRef> quarters(TenantId tenant, int year, UUID personId, String flow) {
    int bucket = year * 10;
    return jdbc.sql(
            """
            SELECT q.period_bucket - ?, q.period_id, q.id, q.status, q.final_rating_code, s.id,
                   s.snapshot_hash, s.total_score, s.policy_version, s.policy_pack_version_id
              FROM appraisal.appraisal_profile q
              LEFT JOIN appraisal.score_snapshot s
                ON s.period_bucket = q.period_bucket AND s.tenant_id = q.tenant_id
               AND s.profile_id = q.id AND s.superseded_by_id IS NULL
             WHERE q.period_bucket = ANY(?::int[]) AND q.tenant_id = ? AND q.person_id = ?
               AND q.flow = ?
             ORDER BY q.period_bucket
            """)
        .params(
            bucket,
            new Integer[] {bucket + 1, bucket + 2, bucket + 3, bucket + 4},
            tenant.value(),
            personId,
            flow)
        .query(
            (rs, i) ->
                new QuarterRef(
                    rs.getInt(1),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getObject(6, UUID.class),
                    rs.getBytes(7),
                    rs.getBigDecimal(8),
                    rs.getString(9),
                    rs.getObject(10, UUID.class)))
        .list();
  }

  @Override
  public void recordScore(
      TenantId tenant,
      int year,
      UUID profileId,
      BigDecimal annualScore,
      String systemLevel,
      UUID actor,
      Instant at) {
    jdbc.sql(
            "UPDATE appraisal.annual_rating SET annual_score = ?, system_level = ?, updated_at = ?,"
                + " updated_by = ?, row_version = row_version + 1 WHERE period_bucket = ?"
                + " AND tenant_id = ? AND profile_id = ?")
        .params(
            annualScore,
            systemLevel,
            Timestamp.from(at),
            actor,
            year * 10,
            tenant.value(),
            profileId)
        .update();
  }

  @Override
  public List<AnnualRow> page(TenantId tenant, Filter filter, int limit) {
    StringBuilder sql = new StringBuilder(ROW_SQL);
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    where(tenant, filter, sql, params, true);
    sql.append(" ORDER BY ar.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> row(rs)).list();
  }

  @Override
  public Counts counts(TenantId tenant, Filter filter) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT count(*) FILTER (WHERE p.status <> 'CHUA_LAP' OR ar.annual_score IS NOT NULL),
                   count(*),
                   count(*) FILTER (WHERE ar.has_khtnv_quarter),
                   count(*) FILTER (WHERE ar.reassign_flag),
                   count(*) FILTER (WHERE NOT EXISTS (
                         SELECT 1 FROM appraisal.prospect_group g
                          WHERE g.period_bucket = ar.period_bucket AND g.tenant_id = ar.tenant_id
                            AND g.person_id = ar.person_id))
              FROM appraisal.annual_rating ar
              JOIN appraisal.appraisal_profile p
                ON p.period_bucket = ar.period_bucket AND p.tenant_id = ar.tenant_id
               AND p.id = ar.profile_id
              LEFT JOIN org_replica.person_v pv ON pv.tenant_id = p.tenant_id AND pv.id = p.person_id
             WHERE ar.tenant_id = ?
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    where(tenant, filter, sql, params, false);
    return jdbc.sql(sql.toString())
        .params(params)
        .query(
            (rs, i) ->
                new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
        .single();
  }

  @Override
  public Optional<AnnualRow> find(TenantId tenant, UUID id) {
    return jdbc.sql(ROW_SQL + " AND ar.id = ?")
        .params(tenant.value(), id)
        .query((rs, i) -> row(rs))
        .optional();
  }

  private static void where(
      TenantId tenant, Filter f, StringBuilder sql, List<Object> params, boolean paging) {
    sql.append(" AND ar.period_id = ?");
    params.add(f.periodId());
    if (f.flow() != null) {
      sql.append(" AND ar.flow = ?");
      params.add(f.flow());
    }
    if (f.unitId() != null) {
      sql.append(
          " AND p.org_unit_id IN (SELECT uc.descendant_id FROM org_replica.unit_closure_v uc"
              + " WHERE uc.tenant_id = ? AND uc.tree = ? AND uc.ancestor_id = ?)");
      params.add(tenant.value());
      params.add("DANG".equals(f.flow()) ? "DANG" : "CHUYEN_MON");
      params.add(f.unitId());
    }
    if (f.q() != null && !f.q().isBlank()) {
      sql.append(" AND pv.full_name ILIKE ?");
      params.add("%" + JdbcReviewRepository.escapeLike(f.q().trim()) + "%");
    }
    if (paging && f.linkageOnly()) {
      sql.append(" AND (ar.block_reason IS NOT NULL OR ar.reassign_flag)");
    }
    if (paging && f.after() != null) {
      sql.append(" AND ar.id > ?");
      params.add(f.after());
    }
    sql.append(" AND (").append(f.scope().sql()).append(')');
    params.addAll(f.scope().params());
  }

  private static AnnualRow row(ResultSet rs) throws SQLException {
    return new AnnualRow(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getObject(3, UUID.class),
        rs.getString(4),
        rs.getObject(5, UUID.class),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        quarterRatings(rs.getArray(23)),
        rs.getBigDecimal(9),
        rs.getString(10),
        rs.getString(11),
        rs.getString(12),
        rs.getBoolean(13),
        rs.getBoolean(14),
        rs.getString(15),
        rs.getBoolean(16),
        rs.getBoolean(17),
        rs.getLong(18),
        rs.getObject(19, UUID.class),
        rs.getInt(20),
        rs.getObject(21, UUID.class),
        rs.getObject(22, UUID.class));
  }

  /** Bốn ô quý theo thứ tự; quý chưa có mức quyết định là {@code null}. */
  private static List<String> quarterRatings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    return Arrays.stream((String[]) array.getArray())
        .map(v -> v == null || v.isEmpty() ? null : v)
        .toList();
  }
}
