package vn.viettel.okrkpi.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.TransactionManager;
import vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.observability.TenantIdProvider;

class TenantAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TenantAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  TransactionManagerCustomizationAutoConfiguration.class,
                  TransactionAutoConfiguration.class))
          .withBean(DataSource.class, SimpleDriverDataSource::new);

  @Test
  void thayTransactionManagerMacDinhBangBanDatTenant() {
    runner
        .withPropertyValues("spring.transaction.default-timeout=5s")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(TransactionManager.class);
              TenantDataSourceTransactionManager tm =
                  ctx.getBean(TenantDataSourceTransactionManager.class);
              assertThat(tm.getDefaultTimeout()).isEqualTo(5);
            });
  }

  @Test
  void tonTrongTransactionManagerCuaService() {
    runner
        .withBean(
            "transactionManager",
            DataSourceTransactionManager.class,
            () -> new DataSourceTransactionManager(new SimpleDriverDataSource()))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(TenantDataSourceTransactionManager.class));
  }

  @Test
  void tenantChoNhatKyLayTuNguCanhDaKiem() {
    runner.run(
        ctx -> {
          TenantIdProvider provider = ctx.getBean(TenantIdProvider.class);
          TenantId a = TenantId.of(UUID.randomUUID());
          assertThat(provider.currentTenant(null)).isEmpty();
          TenantContext.run(a, () -> assertThat(provider.currentTenant(null)).contains(a));
        });
  }

  @Test
  void khongCoDataSourceThiKhongTaoTransactionManager() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TenantAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(TransactionManager.class));
  }

  @Test
  void dangKyBoLocSauSpringSecurity() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                ProblemAutoConfiguration.class,
                TenantAutoConfiguration.class))
        .withPropertyValues("okrkpi.tenant.claim=tid")
        .run(
            ctx -> {
              FilterRegistrationBean<?> reg =
                  ctx.getBean("tenantContextFilter", FilterRegistrationBean.class);
              assertThat(reg.getFilter()).isInstanceOf(TenantContextFilter.class);
              assertThat(reg.getOrder())
                  .isEqualTo(
                      org.springframework.boot.autoconfigure.security.SecurityProperties
                              .DEFAULT_FILTER_ORDER
                          + 1);
              assertThat(ctx.getBean(TenantProperties.class).claim()).isEqualTo("tid");
            });
  }
}
