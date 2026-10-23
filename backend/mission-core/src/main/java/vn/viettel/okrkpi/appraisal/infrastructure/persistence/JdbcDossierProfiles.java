package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@link DossierProfilesApi} trên {@code appraisal.appraisal_profile} và ảnh chụp điểm hiện hành
 * (CORE-06-06). Mức là {@code final_rating_code}, thiếu thì mức hiện hành ở bước quyết định. Mã và
 * phiên bản Policy Pack lấy theo gắn của kỳ cho tổ hợp luồng, khối, đơn vị (tem phiên bản); danh
 * mục biểu mẫu và phiên bản mẫu đọc ở tham số của phiên bản gói mà hồ sơ đã ghim (suy luận, wiki
 * trang 14).
 */
public class JdbcDossierProfiles implements DossierProfilesApi {

  static final Set<String> FORM_CODES =
      Set.of("MAU_1A", "MAU_1B", "MAU_1C", "MAU_1D", "MAU_2", "MAU_3", "BANG_CHAM_DIEM");

  private static final String SELECT =
      """
      SELECT p.id, p.person_id, p.period_id, p.period_bucket, p.org_unit_id, p.flow, p.status,
             p.subject_group_id, p.is_head, p.policy_pack_version_id, p.current_snapshot_id,
             coalesce(p.final_rating_code, q.rating_code) AS rating_code, s.total_score
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.score_snapshot s ON s.period_bucket = p.period_bucket
             AND s.tenant_id = p.tenant_id AND s.id = p.current_snapshot_id
        LEFT JOIN LATERAL (
              SELECT r.rating_code FROM appraisal.rating_proposal r
               WHERE r.period_bucket = p.period_bucket AND r.tenant_id = p.tenant_id
                 AND r.profile_id = p.id AND r.level_kind = 'QUYET_DINH' AND r.is_current
               ORDER BY r.proposed_at DESC LIMIT 1) q ON true
       WHERE p.tenant_id = :tenant
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final PolicyQueryApi policies;
  private final java.util.function.Supplier<AppraisalProfileService> lifecycle;

  public JdbcDossierProfiles(
      JdbcClient jdbc, PlatformTransactionManager tm, PolicyQueryApi policies) {
    this(jdbc, tm, policies, () -> null);
  }

  public JdbcDossierProfiles(
      JdbcClient jdbc,
      PlatformTransactionManager tm,
      PolicyQueryApi policies,
      java.util.function.Supplier<AppraisalProfileService> lifecycle) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(Objects.requireNonNull(tm, "tm"));
    this.tx.setReadOnly(true);
    this.policies = policies;
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
  }

  @Override
  public Optional<UnitProfile> find(TenantId tenant, UUID profileId) {
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
  public List<UnitProfile> unitProfiles(TenantId tenant, UUID periodId, UUID orgUnitId) {
    return load(
        tenant,
        () ->
            jdbc.sql(
                    SELECT
                        + " AND p.period_id = :period AND p.org_unit_id = :unit"
                        + " ORDER BY p.id")
                .param("tenant", tenant.value())
                .param("period", periodId)
                .param("unit", orgUnitId)
                .query(this::row)
                .list());
  }

  @Override
  public List<UUID> undecidedPersons(TenantId tenant, UUID periodId, UUID orgUnitId) {
    return call(
        tenant,
        () ->
            tx.execute(
                s ->
                    jdbc.sql(
                            "SELECT DISTINCT person_id FROM appraisal.appraisal_profile"
                                + " WHERE tenant_id = :tenant AND period_id = :period"
                                + " AND org_unit_id = :unit AND status NOT IN (:decided)")
                        .param("tenant", tenant.value())
                        .param("period", periodId)
                        .param("unit", orgUnitId)
                        .param("decided", List.copyOf(DECIDED))
                        .query(UUID.class)
                        .list()));
  }

  @Override
  public boolean anyDecided(TenantId tenant, UUID periodId, UUID orgUnitId) {
    Boolean any =
        call(
            tenant,
            () ->
                tx.execute(
                    s ->
                        jdbc.sql(
                                "SELECT EXISTS (SELECT 1 FROM appraisal.appraisal_profile"
                                    + " WHERE tenant_id = :tenant AND period_id = :period"
                                    + " AND org_unit_id = :unit AND status IN (:decided))")
                            .param("tenant", tenant.value())
                            .param("period", periodId)
                            .param("unit", orgUnitId)
                            .param("decided", List.copyOf(DECIDED))
                            .query(Boolean.class)
                            .single()));
    return Boolean.TRUE.equals(any);
  }

  @Override
  public int lock(TenantId tenant, Collection<UUID> profileIds, UUID actor) {
    AppraisalProfileService service = lifecycle.get();
    if (service == null || profileIds.isEmpty()) {
      return 0;
    }
    List<UUID> signed =
        call(
            tenant,
            () ->
                tx.execute(
                    s ->
                        jdbc.sql(
                                "SELECT id FROM appraisal.appraisal_profile WHERE tenant_id ="
                                    + " :tenant AND id IN (:ids) AND status = 'DA_KY_SO'")
                            .param("tenant", tenant.value())
                            .param("ids", List.copyOf(profileIds))
                            .query(UUID.class)
                            .list()));
    int locked = 0;
    for (UUID id : signed) {
      try {
        call(tenant, () -> service.transition(tenant, id, null, actor, (p, row) -> p.lock(actor)));
        locked++;
      } catch (RuntimeException e) {
        // Hồ sơ đổi trạng thái giữa lúc đọc và lúc khoá: giữ nguyên.
      }
    }
    return locked;
  }

  // ---------------------------------------------------------------- đọc

  private record Row(UnitProfile base, UUID subjectGroupId, UUID packVersionId) {}

  private record PackKey(String flow, UUID subjectGroupId, UUID versionId) {}

  private Row row(ResultSet rs, int i) throws SQLException {
    return new Row(
        new UnitProfile(
            rs.getObject("id", UUID.class),
            rs.getObject("person_id", UUID.class),
            rs.getObject("period_id", UUID.class),
            rs.getInt("period_bucket"),
            rs.getObject("org_unit_id", UUID.class),
            rs.getString("flow"),
            rs.getString("status"),
            rs.getString("rating_code"),
            rs.getBigDecimal("total_score"),
            rs.getObject("current_snapshot_id", UUID.class),
            rs.getBoolean("is_head"),
            null),
        rs.getObject("subject_group_id", UUID.class),
        rs.getObject("policy_pack_version_id", UUID.class));
  }

  private List<UnitProfile> load(TenantId tenant, Callable<List<Row>> query) {
    List<Row> rows = call(tenant, () -> tx.execute(s -> callUnchecked(query)));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    Map<PackKey, Pack> packs = new HashMap<>();
    List<UnitProfile> out = new ArrayList<>(rows.size());
    for (Row r : rows) {
      UnitProfile b = r.base();
      Pack pack =
          packs.computeIfAbsent(
              new PackKey(b.flow(), r.subjectGroupId(), r.packVersionId()),
              k -> call(tenant, () -> pack(tenant, b, k)));
      out.add(
          new UnitProfile(
              b.id(),
              b.personId(),
              b.periodId(),
              b.periodBucket(),
              b.orgUnitId(),
              b.flow(),
              b.status(),
              b.ratingCode(),
              b.score(),
              b.scoreSnapshotId(),
              b.head(),
              pack));
    }
    return out;
  }

  private Pack pack(TenantId tenant, UnitProfile p, PackKey k) {
    if (policies == null) {
      return new Pack(null, null, DEFAULT_PERSONAL_FORMS, DEFAULT_UNIT_FORMS, Map.of());
    }
    Optional<ResolvedPack> resolved =
        policies.resolve(
            tenant, PeriodId.of(p.periodId()), k.flow(), k.subjectGroupId(), p.orgUnitId());
    String code = resolved.map(ResolvedPack::code).orElse(null);
    String version =
        resolved
            .map(ResolvedPack::version)
            .orElseGet(
                () ->
                    k.versionId() == null
                        ? null
                        : policies
                            .content(tenant, k.versionId())
                            .map(PolicyQueryApi.PolicyContent::version)
                            .orElse(null));
    UUID versionId =
        k.versionId() != null ? k.versionId() : resolved.map(ResolvedPack::versionId).orElse(null);
    if (versionId == null) {
      return new Pack(code, version, DEFAULT_PERSONAL_FORMS, DEFAULT_UNIT_FORMS, Map.of());
    }
    return new Pack(
        code,
        version,
        forms(policies.parameter(tenant, versionId, PERSONAL_FORMS_PARAM), DEFAULT_PERSONAL_FORMS),
        forms(policies.parameter(tenant, versionId, UNIT_FORMS_PARAM), DEFAULT_UNIT_FORMS),
        versions(policies.parameter(tenant, versionId, TEMPLATE_VERSIONS_PARAM)));
  }

  /** Danh sách mã biểu mẫu hợp lệ theo thứ tự khai báo; tham số vắng hoặc sai kiểu thì mặc định. */
  static List<String> forms(Optional<Object> value, List<String> fallback) {
    if (value.isEmpty() || !(value.get() instanceof Collection<?> list)) {
      return fallback;
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      String code = o == null ? null : o.toString().strip();
      if (code != null && FORM_CODES.contains(code) && !out.contains(code)) {
        out.add(code);
      }
    }
    return out;
  }

  /** Bảng mã biểu mẫu → phiên bản mẫu (số nguyên dương); mục sai kiểu bị bỏ qua. */
  static Map<String, Integer> versions(Optional<Object> value) {
    if (value.isEmpty() || !(value.get() instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      String code = e.getKey() == null ? null : e.getKey().toString();
      Integer v = null;
      if (e.getValue() instanceof Number n && n.intValue() > 0) {
        v = n.intValue();
      } else if (e.getValue() instanceof String s && s.strip().matches("[1-9]\\d{0,5}")) {
        v = Integer.parseInt(s.strip());
      }
      if (code != null && FORM_CODES.contains(code) && v != null) {
        out.put(code, v);
      }
    }
    return out;
  }

  private static <T> T callUnchecked(Callable<T> work) {
    try {
      return work.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T call(TenantId tenant, Callable<T> work) {
    try {
      return TenantContext.call(tenant, work);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
