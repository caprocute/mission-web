package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@link ComplaintProfilesApi} trên {@code appraisal.appraisal_profile}, ảnh chụp điểm hiện hành và
 * mức hiện hành của từng bước. Thời điểm công bố là lúc ký quyết định ({@code rating_proposal} mức
 * {@code QUYET_DINH}), thiếu thì lấy {@code decided_at}. Cấu phần lấy từ {@code group_scores_json}
 * của ảnh chụp, cộng tổng điểm và mức của các bước thẩm định, đề xuất, quyết định (suy luận, wiki
 * trang 14). Lệnh chuyển {@code KHIEU_NAI} chạy qua {@link AppraisalProfileService}.
 */
public class JdbcComplaintProfiles implements ComplaintProfilesApi {

  private static final String SELECT =
      """
      SELECT p.id, p.person_id, p.period_id, p.period_bucket, p.org_unit_id, p.status,
             coalesce(p.final_rating_code, q.rating_code) AS rating_code, s.total_score,
             coalesce(q.signed_at, p.decided_at, p.updated_at) AS published_at,
             p.policy_pack_version_id, s.group_scores_json::text AS groups
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.score_snapshot s ON s.period_bucket = p.period_bucket
             AND s.tenant_id = p.tenant_id AND s.id = p.current_snapshot_id
        LEFT JOIN LATERAL (
              SELECT r.rating_code, r.signed_at FROM appraisal.rating_proposal r
               WHERE r.period_bucket = p.period_bucket AND r.tenant_id = p.tenant_id
                 AND r.profile_id = p.id AND r.level_kind = 'QUYET_DINH' AND r.is_current
               ORDER BY r.proposed_at DESC LIMIT 1) q ON true
       WHERE p.tenant_id = :tenant
      """;

  private static final Map<String, String> STAGE_LABELS =
      Map.of(
          "THAM_DINH", "Mức xếp loại ở bước thẩm định",
          "DE_XUAT", "Mức xếp loại ở bước đề xuất",
          "QUYET_DINH", "Mức xếp loại ở bước quyết định");

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final AppraisalProfileService profiles;
  private final PolicyQueryApi policies;
  private final ObjectMapper json;

  public JdbcComplaintProfiles(
      JdbcClient jdbc,
      PlatformTransactionManager tm,
      AppraisalProfileService profiles,
      PolicyQueryApi policies,
      ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(Objects.requireNonNull(tm, "tm"));
    this.tx.setReadOnly(true);
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.policies = policies;
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<Profile> signedProfilesOf(TenantId tenant, UUID personId) {
    return load(
        tenant,
        () ->
            jdbc.sql(
                    SELECT
                        + " AND p.person_id = :person AND p.status = 'DA_KY_SO'"
                        + " ORDER BY published_at DESC, p.id")
                .param("tenant", tenant.value())
                .param("person", personId)
                .query(this::row)
                .list());
  }

  @Override
  public Optional<Profile> find(TenantId tenant, UUID profileId) {
    return load(
            tenant,
            () ->
                jdbc.sql(SELECT + " AND p.id = :id")
                    .param("tenant", tenant.value())
                    .param("id", profileId)
                    .query(this::row)
                    .list())
        .stream()
        .findFirst();
  }

  @Override
  public boolean fileComplaint(TenantId tenant, UUID profileId, UUID actorId) {
    return call(
        tenant,
        () -> {
          String status = find(tenant, profileId).orElseThrow(NoSuchElementException::new).status();
          if (AppraisalStatus.KHIEU_NAI.name().equals(status)) {
            return false;
          }
          profiles.transition(tenant, profileId, null, actorId, (p, r) -> p.fileComplaint(actorId));
          return true;
        });
  }

  // ---------------------------------------------------------------- đọc

  /** Dòng hồ sơ chưa có cấu phần và thời hạn; {@link #load} bổ sung. */
  private record Row(Profile base, UUID packVersionId, String groups) {}

  private Row row(ResultSet rs, int i) throws SQLException {
    Timestamp published = rs.getTimestamp("published_at");
    return new Row(
        new Profile(
            rs.getObject("id", UUID.class),
            rs.getObject("person_id", UUID.class),
            rs.getObject("period_id", UUID.class),
            rs.getInt("period_bucket"),
            rs.getObject("org_unit_id", UUID.class),
            rs.getString("status"),
            rs.getString("rating_code"),
            rs.getBigDecimal("total_score"),
            published == null ? null : published.toInstant(),
            DEFAULT_DEADLINE_DAYS,
            List.of()),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getString("groups"));
  }

  private List<Profile> load(TenantId tenant, Supplier<List<Row>> query) {
    List<Row> rows = call(tenant, () -> tx.execute(s -> query.get()));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, Map<String, String>> levels =
        call(tenant, () -> tx.execute(s -> currentLevels(tenant, rows)));
    Map<UUID, Integer> days = new HashMap<>();
    List<Profile> out = new ArrayList<>();
    for (Row r : rows) {
      Profile b = r.base();
      int d =
          r.packVersionId() == null
              ? DEFAULT_DEADLINE_DAYS
              : days.computeIfAbsent(r.packVersionId(), v -> deadlineDays(tenant, v));
      out.add(
          new Profile(
              b.id(),
              b.personId(),
              b.periodId(),
              b.periodBucket(),
              b.orgUnitId(),
              b.status(),
              b.ratingCode(),
              b.score(),
              b.publishedAt(),
              d,
              targets(b, r.groups(), levels.getOrDefault(b.id(), Map.of()))));
    }
    return out;
  }

  /** Mức hiện hành theo bước của từng hồ sơ. */
  private Map<UUID, Map<String, String>> currentLevels(TenantId tenant, List<Row> rows) {
    Map<UUID, Map<String, String>> out = new HashMap<>();
    jdbc.sql(
            "SELECT profile_id, level_kind, rating_code FROM appraisal.rating_proposal"
                + " WHERE tenant_id = :tenant AND is_current AND profile_id IN (:ids)"
                + " AND level_kind IN ('THAM_DINH', 'DE_XUAT', 'QUYET_DINH')")
        .param("tenant", tenant.value())
        .param("ids", rows.stream().map(r -> r.base().id()).toList())
        .query(
            rs -> {
              out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                  .put(rs.getString(2), rs.getString(3));
            });
    return out;
  }

  private List<Target> targets(Profile p, String groups, Map<String, String> levels) {
    List<Target> out = new ArrayList<>();
    if (groups != null) {
      try {
        JsonNode node = json.readTree(groups);
        Iterator<Map.Entry<String, JsonNode>> it = node.isObject() ? node.fields() : null;
        while (it != null && it.hasNext()) {
          Map.Entry<String, JsonNode> e = it.next();
          JsonNode v = e.getValue();
          String label =
              v.isObject() && v.hasNonNull("label") ? v.get("label").asText() : e.getKey();
          JsonNode value = v.isObject() ? v.get("value") : v;
          if (value != null && value.isNumber()) {
            label = label + " · " + number(value.decimalValue());
          }
          out.add(new Target(e.getKey(), "THAM_DINH", truncate(label)));
        }
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        // Ảnh chụp cũ không có nhóm điểm hợp lệ: chỉ còn tổng điểm và các mức.
      }
    }
    String total = p.score() == null ? "Tổng điểm" : "Tổng điểm · " + number(p.score()) + " điểm";
    out.add(new Target("TONG_DIEM", "THAM_DINH", total));
    for (String stage : List.of("THAM_DINH", "DE_XUAT", "QUYET_DINH")) {
      if (levels.containsKey(stage)) {
        out.add(new Target("XEP_LOAI." + stage, stage, STAGE_LABELS.get(stage)));
      }
    }
    return out;
  }

  private int deadlineDays(TenantId tenant, UUID versionId) {
    if (policies == null) {
      return DEFAULT_DEADLINE_DAYS;
    }
    Optional<Object> v = policies.parameter(tenant, versionId, DEADLINE_PARAM);
    if (v.isPresent() && v.get() instanceof Number n && n.intValue() > 0) {
      return n.intValue();
    }
    if (v.isPresent() && v.get() instanceof String s && s.strip().matches("\\d{1,3}")) {
      return Integer.parseInt(s.strip());
    }
    return DEFAULT_DEADLINE_DAYS;
  }

  private static String number(BigDecimal v) {
    DecimalFormat f = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.of("vi")));
    return f.format(v);
  }

  private static String truncate(String s) {
    return s.length() <= 200 ? s : s.substring(0, 200);
  }

  private static <T> T call(TenantId tenant, java.util.concurrent.Callable<T> work) {
    try {
      return TenantContext.call(tenant, work);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
