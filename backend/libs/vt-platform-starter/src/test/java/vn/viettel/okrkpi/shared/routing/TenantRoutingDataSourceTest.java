package vn.viettel.okrkpi.shared.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

class TenantRoutingDataSourceTest {

  private static final UUID TENANT_POOL_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000001");
  private static final UUID TENANT_BRIDGE_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000002");
  private static final UUID TENANT_SILO_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000003");
  private static final UUID TENANT_OTHER_CELL_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000004");
  private static final UUID TENANT_MIGRATING_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000005");
  private static final UUID TENANT_SUSPENDED_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000006");
  private static final UUID TENANT_UNKNOWN_ID =
      UUID.fromString("0195f3a0-0000-7000-8000-000000000099");

  @Test
  void routesPoolBridgeAndSiloToCorrectDataSourcesAndRejectsInvalidCellOrStatus() throws Exception {
    TenantDirectory directory =
        new TenantDirectory(
            new TenantRegistryClient() {
              @Override
              public Optional<TenantCellRoute> resolveByTenantId(UUID tenantId) {
                return Optional.empty();
              }

              @Override
              public Optional<TenantCellRoute> resolveByCode(String tenantCode) {
                return Optional.empty();
              }
            });

    Instant expiry = Instant.now().plusSeconds(600);
    directory.put(
        new TenantCellRoute(
            TENANT_POOL_ID,
            "phuong_1",
            "cell-hcm-01",
            null,
            TenancyModel.POOL,
            "td_pool",
            "DANG_VAN_HANH",
            List.of(),
            1L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_BRIDGE_ID,
            "hcm_a",
            "cell-hcm-01",
            null,
            TenancyModel.BRIDGE,
            "td_hcm_a",
            "DANG_VAN_HANH",
            List.of(),
            1L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_SILO_ID,
            "bo_x",
            "cell-hcm-01",
            null,
            TenancyModel.SILO,
            "td_bo_x",
            "DANG_VAN_HANH",
            List.of(),
            1L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_OTHER_CELL_ID,
            "hn_a",
            "cell-hn-01",
            null,
            TenancyModel.BRIDGE,
            "td_hn_a",
            "DANG_VAN_HANH",
            List.of(),
            1L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_MIGRATING_ID,
            "hcm_moving",
            "cell-hcm-01",
            null,
            TenancyModel.BRIDGE,
            "td_hcm_moving",
            "DANG_CHUYEN_CELL",
            List.of(),
            1L,
            expiry));
    directory.put(
        new TenantCellRoute(
            TENANT_SUSPENDED_ID,
            "hcm_paused",
            "cell-hcm-01",
            null,
            TenancyModel.BRIDGE,
            "td_hcm_paused",
            "TAM_NGUNG",
            List.of(),
            1L,
            expiry));

    StubDataSource poolDs = new StubDataSource("td_pool");
    TenantRoutingDataSource routingDs =
        new TenantRoutingDataSource(directory, "cell-hcm-01", poolDs, StubDataSource::new);

    // 1. Missing TenantContext -> throws MISSING_TENANT_CONTEXT
    assertThatThrownBy(routingDs::resolveTargetDatabaseName)
        .isInstanceOf(TenantRoutingException.class)
        .extracting(ex -> ((TenantRoutingException) ex).reason())
        .isEqualTo(TenantRoutingException.Reason.MISSING_TENANT_CONTEXT);

    // 2. POOL tenant -> td_pool
    TenantContext.run(
        TenantId.of(TENANT_POOL_ID),
        () -> {
          assertThat(routingDs.resolveTargetDatabaseName()).isEqualTo("td_pool");
          assertThat(routingDs.resolveTargetDataSource()).isSameAs(poolDs);
        });

    // 3. BRIDGE tenant -> td_hcm_a (lazy created)
    TenantContext.run(
        TenantId.of(TENANT_BRIDGE_ID),
        () -> {
          assertThat(routingDs.resolveTargetDatabaseName()).isEqualTo("td_hcm_a");
          DataSource ds = routingDs.resolveTargetDataSource();
          assertThat(ds).isInstanceOf(StubDataSource.class);
          assertThat(((StubDataSource) ds).name).isEqualTo("td_hcm_a");
        });

    // 4. SILO tenant -> td_bo_x
    TenantContext.run(
        TenantId.of(TENANT_SILO_ID),
        () -> assertThat(routingDs.resolveTargetDatabaseName()).isEqualTo("td_bo_x"));

    // 5. Wrong cell -> throws WRONG_CELL
    TenantContext.run(
        TenantId.of(TENANT_OTHER_CELL_ID),
        () ->
            assertThatThrownBy(routingDs::resolveTargetDatabaseName)
                .isInstanceOf(TenantRoutingException.class)
                .satisfies(
                    ex -> {
                      TenantRoutingException tre = (TenantRoutingException) ex;
                      assertThat(tre.reason()).isEqualTo(TenantRoutingException.Reason.WRONG_CELL);
                      assertThat(tre.expectedCell()).isEqualTo("cell-hn-01");
                      assertThat(tre.actualCell()).isEqualTo("cell-hcm-01");
                      assertThat(tre.tenantKey()).isEqualTo("hn_a");
                    }));

    // 6. Migrating tenant -> throws TENANT_MIGRATING_OR_UNAVAILABLE
    TenantContext.run(
        TenantId.of(TENANT_MIGRATING_ID),
        () ->
            assertThatThrownBy(routingDs::resolveTargetDatabaseName)
                .isInstanceOf(TenantRoutingException.class)
                .extracting(ex -> ((TenantRoutingException) ex).reason())
                .isEqualTo(TenantRoutingException.Reason.TENANT_MIGRATING_OR_UNAVAILABLE));

    // 6b. Tenant TAM_NGUNG vẫn có kết nối để đọc (TL-04 mục 11.4); ghi bị bộ lọc HTTP chặn trước.
    TenantContext.run(
        TenantId.of(TENANT_SUSPENDED_ID),
        () -> assertThat(routingDs.resolveTargetDatabaseName()).isEqualTo("td_hcm_paused"));

    // 7. Unknown tenant -> throws TENANT_NOT_FOUND
    TenantContext.run(
        TenantId.of(TENANT_UNKNOWN_ID),
        () ->
            assertThatThrownBy(routingDs::resolveTargetDatabaseName)
                .isInstanceOf(TenantRoutingException.class)
                .extracting(ex -> ((TenantRoutingException) ex).reason())
                .isEqualTo(TenantRoutingException.Reason.TENANT_NOT_FOUND));

    routingDs.registerDataSource("td_custom", new StubDataSource("td_custom"));
    assertThat(routingDs.managedDataSources()).containsKeys("td_pool", "td_hcm_a", "td_custom");
    routingDs.close();
    assertThat(poolDs.closed.get()).isTrue();
  }

  @Test
  void autoConfigurationRegistersBeansWhenEnabled() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ProblemAutoConfiguration.class, TenantRoutingAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "vt.platform.routing.enabled=true",
            "vt.platform.routing.cell-code=cell-hcm-01",
            "vt.platform.routing.registry-base-url=http://127.0.0.1:18080")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TenantRegistryClient.class);
              assertThat(context).hasSingleBean(TenantDirectory.class);
              assertThat(context).hasBean("tenantCellRoutingFilterRegistration");
            });
  }

  private static final class StubDataSource implements DataSource, Closeable {
    private final String name;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    StubDataSource(String name) {
      this.name = name;
    }

    @Override
    public void close() {
      closed.set(true);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return null;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return null;
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
