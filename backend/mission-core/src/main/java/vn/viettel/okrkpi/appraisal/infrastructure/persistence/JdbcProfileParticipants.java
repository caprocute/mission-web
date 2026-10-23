package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@link ProfileParticipantsApi} trên {@code appraisal.rating_proposal}, giao dịch chỉ đọc riêng.
 */
public class JdbcProfileParticipants implements ProfileParticipantsApi {

  private static final String LEVELS = "('THAM_DINH', 'QUYET_DINH')";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final EvidenceQueryApi evidence;

  public JdbcProfileParticipants(JdbcClient jdbc, PlatformTransactionManager tm) {
    this(jdbc, tm, null);
  }

  /**
   * @param evidence cổng nghiệm thu minh chứng của MOD-03; vắng thì nhóm {@code acceptor} rỗng
   */
  public JdbcProfileParticipants(
      JdbcClient jdbc, PlatformTransactionManager tm, EvidenceQueryApi evidence) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(Objects.requireNonNull(tm, "tm"));
    this.tx.setReadOnly(true);
    this.evidence = evidence;
  }

  @Override
  public Set<UUID> reviewersOf(TenantId tenant, PeriodId periodId, Collection<UUID> profileIds) {
    if (profileIds.isEmpty()) {
      return Set.of();
    }
    return query(
        tenant,
        "SELECT DISTINCT actor_id FROM appraisal.rating_proposal WHERE tenant_id = :tenant"
            + " AND period_id = :period AND level_kind IN "
            + LEVELS
            + " AND profile_id IN (:ids)",
        periodId,
        profileIds);
  }

  @Override
  public Set<UUID> reviewersOfUnits(TenantId tenant, PeriodId periodId, Collection<UUID> unitIds) {
    String unitFilter = unitIds.isEmpty() ? "" : " AND p.org_unit_id IN (:ids)";
    return query(
        tenant,
        "SELECT DISTINCT r.actor_id FROM appraisal.rating_proposal r"
            + " JOIN appraisal.appraisal_profile p ON p.period_bucket = r.period_bucket"
            + " AND p.tenant_id = r.tenant_id AND p.id = r.profile_id"
            + " WHERE r.tenant_id = :tenant AND r.period_id = :period AND r.level_kind IN "
            + LEVELS
            + unitFilter,
        periodId,
        unitIds);
  }

  @Override
  public Map<UUID, ProfileSubject> subjectsOf(TenantId tenant, Collection<UUID> profileIds) {
    if (profileIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, ProfileSubject> out = new HashMap<>();
    read(
        tenant,
        () ->
            jdbc.sql(
                    "SELECT id, person_id, org_unit_id FROM appraisal.appraisal_profile"
                        + " WHERE tenant_id = :tenant AND id IN (:ids)")
                .param("tenant", tenant.value())
                .param("ids", List.copyOf(profileIds))
                .query(
                    (rs, row) ->
                        out.put(
                            rs.getObject("id", UUID.class),
                            new ProfileSubject(
                                rs.getObject("person_id", UUID.class),
                                rs.getObject("org_unit_id", UUID.class))))
                .list());
    return out;
  }

  @Override
  public Optional<Participants> participantsOf(TenantId tenant, UUID profileId) {
    record Head(UUID person, UUID unit, UUID period) {}
    record Step(String level, UUID actor) {}
    record Read(Head head, List<Step> steps) {}
    Read read =
        read(
            tenant,
            () -> {
              Head head =
                  jdbc.sql(
                          "SELECT person_id, org_unit_id, period_id FROM appraisal.appraisal_profile"
                              + " WHERE tenant_id = :tenant AND id = :id")
                      .param("tenant", tenant.value())
                      .param("id", profileId)
                      .query(
                          (rs, row) ->
                              new Head(
                                  rs.getObject("person_id", UUID.class),
                                  rs.getObject("org_unit_id", UUID.class),
                                  rs.getObject("period_id", UUID.class)))
                      .optional()
                      .orElse(null);
              if (head == null) {
                return null;
              }
              List<Step> steps =
                  jdbc.sql(
                          "SELECT DISTINCT level_kind, actor_id FROM appraisal.rating_proposal"
                              + " WHERE tenant_id = :tenant AND profile_id = :id"
                              + " AND level_kind IN ('THAM_DINH', 'DE_XUAT', 'QUYET_DINH')")
                      .param("tenant", tenant.value())
                      .param("id", profileId)
                      .query(
                          (rs, row) ->
                              new Step(
                                  rs.getString("level_kind"), rs.getObject("actor_id", UUID.class)))
                      .list();
              return new Read(head, steps);
            });
    if (read == null) {
      return Optional.empty();
    }
    Map<String, Set<UUID>> byLevel = new HashMap<>();
    for (Step s : read.steps()) {
      byLevel.computeIfAbsent(s.level(), k -> new LinkedHashSet<>()).add(s.actor());
    }
    Head h = read.head();
    Set<UUID> acceptors =
        evidence == null ? Set.of() : evidence.acceptors(tenant, h.period(), h.person());
    return Optional.of(
        new Participants(
            profileId,
            h.person(),
            h.unit(),
            h.period(),
            acceptors,
            byLevel.getOrDefault("THAM_DINH", Set.of()),
            byLevel.getOrDefault("DE_XUAT", Set.of()),
            byLevel.getOrDefault("QUYET_DINH", Set.of())));
  }

  private Set<UUID> query(TenantId tenant, String sql, PeriodId periodId, Collection<UUID> ids) {
    List<UUID> found =
        read(
            tenant,
            () -> {
              JdbcClient.StatementSpec q =
                  jdbc.sql(sql).param("tenant", tenant.value()).param("period", periodId.value());
              if (!ids.isEmpty()) {
                q = q.param("ids", List.copyOf(ids));
              }
              return q.query(UUID.class).list();
            });
    return found == null ? Set.of() : new LinkedHashSet<>(found);
  }

  private <T> T read(TenantId tenant, java.util.function.Supplier<T> query) {
    try {
      return TenantContext.call(tenant, () -> tx.execute(status -> query.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
