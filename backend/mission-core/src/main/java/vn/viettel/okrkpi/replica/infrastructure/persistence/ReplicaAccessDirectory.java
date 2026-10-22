package vn.viettel.okrkpi.replica.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hiện thực {@link AccessDirectory} của MOD-08 (CORE-08-16) trên {@code org_replica}: tên người,
 * tên đơn vị (phiên bản mới nhất) và cây đơn vị gộp hai cây {@code DANG}, {@code CHUYEN_MON}. Mỗi
 * lời gọi mở giao dịch chỉ đọc riêng với {@code app.tenant_id} của tenant được hỏi.
 */
@Component
public class ReplicaAccessDirectory implements AccessDirectory {

  private static final int MAX_IDS = 500;

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate readTx;

  @Autowired
  public ReplicaAccessDirectory(
      OrgReplicaJdbc jdbc,
      @Qualifier("orgReplicaTransactionManager") PlatformTransactionManager tm) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc.template());
    this.readTx = new TransactionTemplate(tm);
    this.readTx.setReadOnly(true);
  }

  @Override
  public Map<UUID, String> personNames(TenantId tenant, Collection<UUID> personIds) {
    return names(
        tenant,
        personIds,
        "select id, full_name as name from org_replica.person_v where id in (:ids)");
  }

  @Override
  public Map<UUID, String> unitNames(TenantId tenant, Collection<UUID> unitIds) {
    return names(
        tenant,
        unitIds,
        "select distinct on (id) id, name from org_replica.org_unit_v where id in (:ids)"
            + " order by id, valid_from desc");
  }

  @Override
  public List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId) {
    return read(
        tenant,
        () ->
            List.copyOf(
                new LinkedHashSet<>(
                    jdbc.queryForList(
                        "select descendant_id from org_replica.unit_closure_v"
                            + " where ancestor_id = :unit and depth > 0 order by depth, descendant_id",
                        new MapSqlParameterSource("unit", orgUnitId),
                        UUID.class))));
  }

  @Override
  public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
    return read(
        tenant,
        () ->
            List.copyOf(
                new LinkedHashSet<>(
                    jdbc.queryForList(
                        "select ancestor_id from org_replica.unit_closure_v"
                            + " where descendant_id = :unit and depth > 0 order by depth",
                        new MapSqlParameterSource("unit", orgUnitId),
                        UUID.class))));
  }

  private Map<UUID, String> names(TenantId tenant, Collection<UUID> ids, String sql) {
    Set<UUID> wanted = new LinkedHashSet<>(ids);
    wanted.remove(null);
    if (wanted.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> out = new LinkedHashMap<>();
    List<UUID> all = List.copyOf(wanted);
    for (int i = 0; i < all.size(); i += MAX_IDS) {
      List<UUID> chunk = all.subList(i, Math.min(all.size(), i + MAX_IDS));
      read(
          tenant,
          () -> {
            jdbc.query(
                sql,
                new MapSqlParameterSource("ids", chunk),
                rs -> {
                  out.put(rs.getObject("id", UUID.class), rs.getString("name"));
                });
            return null;
          });
    }
    return out;
  }

  private <T> T read(TenantId tenant, Supplier<T> query) {
    try {
      return TenantContext.call(tenant, () -> readTx.execute(status -> query.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
