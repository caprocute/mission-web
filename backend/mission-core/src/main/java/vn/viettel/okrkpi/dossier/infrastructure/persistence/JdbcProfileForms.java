package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.appraisal.api.ProfileFormsApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@link ProfileFormsApi} trên {@code dossier.form_instance}, giao dịch chỉ đọc riêng (CORE-07-09).
 */
public class JdbcProfileForms implements ProfileFormsApi {

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public JdbcProfileForms(DataSource dataSource, PlatformTransactionManager tm) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.tx = new TransactionTemplate(Objects.requireNonNull(tm, "tm"));
    this.tx.setReadOnly(true);
  }

  @Override
  public Optional<ProfileForm> form(TenantId tenant, UUID formInstanceId) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              tx.execute(
                  s ->
                      jdbc.sql(
                              "SELECT profile_id, form_code FROM dossier.form_instance"
                                  + " WHERE tenant_id = ? AND id = ?")
                          .param(tenant.value())
                          .param(formInstanceId)
                          .query(
                              (rs, n) ->
                                  new ProfileForm(
                                      rs.getObject("profile_id", UUID.class),
                                      rs.getString("form_code")))
                          .optional()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
