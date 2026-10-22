package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** {@link RoleHolderApi} trên {@code config.role_binding}, giao dịch chỉ đọc riêng của MOD-08. */
public class JdbcUnitRoleHolders implements RoleHolderApi {

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public JdbcUnitRoleHolders(JdbcClient jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(Objects.requireNonNull(tm, "tm"));
    this.tx.setReadOnly(true);
  }

  @Override
  public List<Holder> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at) {
    return read(
        tenant,
        () ->
            jdbc.sql(
                    "SELECT DISTINCT person_id, scope FROM config.role_binding"
                        + " WHERE tenant_id = :tenant AND role_code = :role"
                        + " AND org_unit_id = :unit AND ("
                        + JdbcRoleBindingRepository.STATUS
                        + ") = 'HIEU_LUC' ORDER BY person_id")
                .param("tenant", tenant.value())
                .param("role", roleCode)
                .param("unit", orgUnitId)
                .param("now", Timestamp.from(at))
                .query(
                    (rs, n) ->
                        new Holder(
                            rs.getObject("person_id", UUID.class),
                            "DON_VI_VA_CAP_DUOI".equals(rs.getString("scope"))))
                .list());
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
