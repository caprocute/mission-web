package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đầu vào tính điểm trên {@code appraisalDataSource} (CORE-04-06). Trang hồ sơ đọc theo khoá chính
 * {@code (period_bucket, tenant_id, id)} sau khi tra {@code period_bucket} của kỳ qua chỉ mục
 * {@code ix_appraisal_profile__status}, nên con trỏ {@code id > ?} ổn định và mỗi trang chỉ quét
 * một phân vùng. Chỉ chọn cột C0, C1.
 */
public class JdbcScoringInputRepository implements ScoringInputRepository {

  private static final String PAGE_SQL =
      """
      SELECT id, person_id, flow, org_unit_id, subject_group_id, is_head, is_leadership,
             policy_pack_version_id
        FROM appraisal.appraisal_profile
       WHERE period_bucket = ? AND tenant_id = ? AND period_id = ?
      """;

  /** Mỗi tiêu chí lấy bước thẩm định nếu có, ngược lại bước tự đánh giá. */
  private static final String CRITERIA_SQL =
      """
      SELECT DISTINCT ON (profile_id, criteria_code) profile_id, criteria_code, met
        FROM appraisal.general_criteria_result
       WHERE period_bucket = ? AND tenant_id = ? AND profile_id = ANY(?::uuid[])
       ORDER BY profile_id, criteria_code, (stage = 'THAM_DINH') DESC
      """;

  private final JdbcClient jdbc;

  public JdbcScoringInputRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<ScoringInput> page(TenantId tenant, UUID periodId, UUID after, int limit) {
    Optional<Integer> bucket =
        jdbc.sql(
                "SELECT period_bucket FROM appraisal.appraisal_profile WHERE tenant_id = ?"
                    + " AND period_id = ? LIMIT 1")
            .params(tenant.value(), periodId)
            .query(Integer.class)
            .optional();
    if (bucket.isEmpty()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder(PAGE_SQL);
    List<Object> params = new ArrayList<>(List.of(bucket.get(), tenant.value(), periodId));
    if (after != null) {
      sql.append(" AND id > ?");
      params.add(after);
    }
    sql.append(" ORDER BY id LIMIT ?");
    params.add(limit);
    List<Row> rows =
        jdbc.sql(sql.toString())
            .params(params)
            .query(
                (rs, i) ->
                    new Row(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class),
                        rs.getBoolean(6),
                        rs.getBoolean(7),
                        rs.getObject(8, UUID.class)))
            .list();
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<SelfAssessmentForm.Criterion>> criteria = new HashMap<>();
    jdbc.sql(CRITERIA_SQL)
        .params(bucket.get(), tenant.value(), rows.stream().map(Row::id).toArray(UUID[]::new))
        .query(
            (ResultSet rs) -> {
              criteria
                  .computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                  .add(new SelfAssessmentForm.Criterion(rs.getString(2), rs.getBoolean(3)));
            });
    return rows.stream()
        .map(
            r ->
                new ScoringInput(
                    r.id(),
                    r.personId(),
                    r.flow(),
                    r.orgUnitId(),
                    r.subjectGroupId(),
                    r.head(),
                    r.leadership(),
                    r.policyPackVersionId(),
                    criteria.getOrDefault(r.id(), List.of())))
        .toList();
  }

  @Override
  public Optional<ProfileKey> profileKey(TenantId tenant, UUID profileId) {
    return jdbc.sql(
            "SELECT id, person_id, period_id, policy_pack_version_id"
                + " FROM appraisal.appraisal_profile WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), profileId)
        .query(
            (rs, i) ->
                new ProfileKey(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class)))
        .optional();
  }

  @Override
  public boolean claim(TenantId tenant, String consumer, UUID eventId, String source) {
    return jdbc.sql(
                "INSERT INTO appraisal.inbox_processed (event_id, consumer, tenant_id, source_topic,"
                    + " kafka_partition, kafka_offset) VALUES (?, ?, ?, ?, -1, -1)"
                    + " ON CONFLICT DO NOTHING")
            .params(eventId, consumer, tenant.value(), source)
            .update()
        == 1;
  }

  @Override
  public int markChanged(TenantId tenant, UUID periodId, Collection<UUID> personIds, Instant at) {
    return jdbc.sql(
            "UPDATE appraisal.appraisal_profile SET inputs_changed_at = ? WHERE tenant_id = ?"
                + " AND period_id = ? AND person_id = ANY(?::uuid[]) AND status <> 'DA_KHOA'")
        .params(Timestamp.from(at), tenant.value(), periodId, personIds.toArray(UUID[]::new))
        .update();
  }

  private record Row(
      UUID id,
      UUID personId,
      String flow,
      UUID orgUnitId,
      UUID subjectGroupId,
      boolean head,
      boolean leadership,
      UUID policyPackVersionId) {}
}
