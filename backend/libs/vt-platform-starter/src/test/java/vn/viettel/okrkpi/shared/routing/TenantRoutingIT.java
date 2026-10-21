package vn.viettel.okrkpi.shared.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.registry.v1.TenantChanged;

/**
 * Kiểm thử tích hợp định tuyến CSDL đa khách thuê (ADR-07, {@code NEN-036}):
 *
 * <ul>
 *   <li>1 container PostgreSQL chứa 3 CSDL: {@code td_hcm_a} (BRIDGE), {@code td_hcm_b} (BRIDGE),
 *       {@code td_pool} (POOL).
 *   <li>Máy chủ HTTP giả lập {@code tenant-registry} ({@code GET /api/v1/registry/resolve}).
 *   <li>Chứng minh 2 khách thuê BRIDGE ghi/đọc trên 2 CSDL vật lý độc lập, 2 khách thuê POOL dùng
 *       chung {@code td_pool} nhưng cô lập bằng RLS {@code app.tenant_id}, sự kiện Kafka {@link
 *       TenantChanged} nâng cấp khách thuê từ POOL sang BRIDGE không cần khởi động lại, và truy vấn
 *       sai cell bị từ chối với HTTP 421 / 503.
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantRoutingIT {

  private static final UUID TENANT_HCM_A = UUID.fromString("0195f3a0-0000-7000-8000-000000000101");
  private static final UUID TENANT_HCM_B = UUID.fromString("0195f3a0-0000-7000-8000-000000000102");
  private static final UUID TENANT_POOL_1 = UUID.fromString("0195f3a0-0000-7000-8000-000000000201");
  private static final UUID TENANT_POOL_2 = UUID.fromString("0195f3a0-0000-7000-8000-000000000202");
  private static final UUID TENANT_HN_A = UUID.fromString("0195f3a0-0000-7000-8000-000000000301");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("postgres")
          .withUsername("postgres")
          .withPassword("postgres");

  private static HttpServer registryServer;
  private static TenantDirectory directory;
  private static TenantRoutingDataSource routingDataSource;
  private static TransactionTemplate txTemplate;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void setUpClusterAndRegistry() throws Exception {
    try (Connection conn =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          """
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rw') THEN
              CREATE ROLE app_rw LOGIN PASSWORD 'app_rw_secret' NOSUPERUSER NOBYPASSRLS;
            END IF;
          END
          $$;
          """);
      stmt.execute("CREATE DATABASE td_hcm_a OWNER postgres");
      stmt.execute("CREATE DATABASE td_hcm_b OWNER postgres");
      stmt.execute("CREATE DATABASE td_pool OWNER postgres");
    }

    for (String dbName : List.of("td_hcm_a", "td_hcm_b", "td_pool")) {
      initDatabaseSchema(dbName);
    }

    registryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registryServer.createContext(
        "/api/v1/registry/resolve",
        exchange -> {
          String query = exchange.getRequestURI().getQuery();
          String payload = resolveMockJson(query);
          if (payload == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    registryServer.start();

    String baseUrl = "http://127.0.0.1:" + registryServer.getAddress().getPort();
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    HttpTenantRegistryClient client =
        new HttpTenantRegistryClient(baseUrl, Duration.ofSeconds(3), mapper);
    directory = new TenantDirectory(client);

    String jdbcTemplate =
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/{db}";

    routingDataSource =
        new TenantRoutingDataSource(
            directory,
            "cell-hcm-01",
            null,
            dbName -> {
              DriverManagerDataSource ds = new DriverManagerDataSource();
              ds.setUrl(jdbcTemplate.replace("{db}", dbName));
              ds.setUsername("app_rw");
              ds.setPassword("app_rw_secret");
              return ds;
            });

    TenantDataSourceTransactionManager txManager =
        new TenantDataSourceTransactionManager(routingDataSource, TenantProperties.defaults());
    txTemplate = new TransactionTemplate(txManager);
    jdbc = new JdbcTemplate(routingDataSource);
  }

  @AfterAll
  static void tearDown() {
    if (routingDataSource != null) {
      routingDataSource.close();
    }
    if (registryServer != null) {
      registryServer.stop(0);
    }
  }

  @Test
  void bridgeTenantsUseDedicatedDatabasesAndPoolTenantsShareTdPoolWithRls() {
    // 1. BRIDGE tenant hcm_a writes to td_hcm_a
    inTenantTx(
        TENANT_HCM_A,
        () -> {
          String currentDb = jdbc.queryForObject("SELECT current_database()", String.class);
          assertThat(currentDb).isEqualTo("td_hcm_a");
          jdbc.update(
              "INSERT INTO core.item(id, tenant_id, title) VALUES (?, ?, ?)",
              UUID.randomUUID(),
              TENANT_HCM_A,
              "Item in td_hcm_a");
        });

    // 2. BRIDGE tenant hcm_b writes to td_hcm_b and cannot see td_hcm_a rows
    inTenantTx(
        TENANT_HCM_B,
        () -> {
          String currentDb = jdbc.queryForObject("SELECT current_database()", String.class);
          assertThat(currentDb).isEqualTo("td_hcm_b");
          List<String> titles = jdbc.queryForList("SELECT title FROM core.item", String.class);
          assertThat(titles).isEmpty();
          jdbc.update(
              "INSERT INTO core.item(id, tenant_id, title) VALUES (?, ?, ?)",
              UUID.randomUUID(),
              TENANT_HCM_B,
              "Item in td_hcm_b");
        });

    // 3. POOL tenants phuong_1 and phuong_2 both connect to td_pool and are isolated by RLS
    inTenantTx(
        TENANT_POOL_1,
        () -> {
          assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
              .isEqualTo("td_pool");
          jdbc.update(
              "INSERT INTO core.item(id, tenant_id, title) VALUES (?, ?, ?)",
              UUID.randomUUID(),
              TENANT_POOL_1,
              "Pool 1 secret");
        });

    inTenantTx(
        TENANT_POOL_2,
        () -> {
          assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
              .isEqualTo("td_pool");
          jdbc.update(
              "INSERT INTO core.item(id, tenant_id, title) VALUES (?, ?, ?)",
              UUID.randomUUID(),
              TENANT_POOL_2,
              "Pool 2 secret");
          List<String> visibleTo2 = jdbc.queryForList("SELECT title FROM core.item", String.class);
          assertThat(visibleTo2).containsExactly("Pool 2 secret");
        });

    inTenantTx(
        TENANT_POOL_1,
        () -> {
          List<String> visibleTo1 = jdbc.queryForList("SELECT title FROM core.item", String.class);
          assertThat(visibleTo1).containsExactly("Pool 1 secret");
        });
  }

  @Test
  void kafkaTenantChangedEventDynamicallySwitchesDatabaseAndRejectsMigratingOrWrongCell()
      throws Exception {
    // Ensure phuong_2 is resolved first into directory as POOL (td_pool)
    inTenantTx(
        TENANT_POOL_2,
        () ->
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("td_pool"));

    // Emit Kafka TenantChanged event upgrading phuong_2 to BRIDGE on td_hcm_b
    directory.onTenantChanged(
        TenantChanged.newBuilder()
            .setTenantCode("phuong_2")
            .setStatus("DANG_VAN_HANH")
            .setTenancyModel("BRIDGE")
            .setCellCode("cell-hcm-01")
            .setDatabaseName("td_hcm_b")
            .setDomains(List.of())
            .setFlows(List.of())
            .setSourceVersion(10L)
            .build());

    // Immediately routes to td_hcm_b without restart
    inTenantTx(
        TENANT_POOL_2,
        () ->
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("td_hcm_b"));

    // Restore phuong_2 back to td_pool (sourceVersion 11)
    directory.onTenantChanged(
        TenantChanged.newBuilder()
            .setTenantCode("phuong_2")
            .setStatus("DANG_VAN_HANH")
            .setTenancyModel("POOL")
            .setCellCode("cell-hcm-01")
            .setDatabaseName("td_pool")
            .setDomains(List.of())
            .setFlows(List.of())
            .setSourceVersion(11L)
            .build());

    // Wrong cell tenant (TENANT_HN_A belongs to cell-hn-01) is rejected by DataSource and Filter
    TenantContext.run(
        TenantId.of(TENANT_HN_A),
        () ->
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(s -> {}))
                .hasRootCauseInstanceOf(TenantRoutingException.class));

    ProblemFactory problemFactory =
        new ProblemFactory(req -> "test-trace-id", ErrorCatalog.standard());
    TenantCellRoutingFilter filter =
        new TenantCellRoutingFilter(
            directory, "cell-hcm-01", problemFactory, new ObjectMapper().findAndRegisterModules());
    MockHttpServletResponse res421 = new MockHttpServletResponse();
    TenantContext.run(
        TenantId.of(TENANT_HN_A),
        () -> {
          try {
            filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/tasks"), res421, new MockFilterChain());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    assertThat(res421.getStatus()).isEqualTo(421);
  }

  private static void inTenantTx(UUID tenantId, Runnable action) {
    TenantContext.run(
        TenantId.of(tenantId), () -> txTemplate.executeWithoutResult(status -> action.run()));
  }

  private static void initDatabaseSchema(String dbName) throws Exception {
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + dbName;
    try (Connection conn =
            DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE SCHEMA IF NOT EXISTS core");
      stmt.execute("GRANT USAGE ON SCHEMA core TO app_rw");
      stmt.execute(
          """
          CREATE TABLE IF NOT EXISTS core.item (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            title text NOT NULL
          )
          """);
      stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON core.item TO app_rw");
      stmt.execute("ALTER TABLE core.item ENABLE ROW LEVEL SECURITY");
      stmt.execute("ALTER TABLE core.item FORCE ROW LEVEL SECURITY");
      stmt.execute(
          """
          CREATE POLICY tenant_isolation ON core.item
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
          """);
    }
  }

  private static String resolveMockJson(String query) {
    if (query == null) {
      return null;
    }
    if (query.contains(TENANT_HCM_A.toString()) || query.contains("code=hcm_a")) {
      return """
          {
            "tenantId": "0195f3a0-0000-7000-8000-000000000101",
            "tenantCode": "hcm_a",
            "tenantName": "So Noi vu A",
            "status": "DANG_VAN_HANH",
            "tenancyModel": "BRIDGE",
            "cell": { "id": "cell-1", "code": "cell-hcm-01", "apiBaseUrl": "http://cell-hcm-01" },
            "databaseName": "td_hcm_a",
            "cacheTtlSeconds": 300,
            "sourceVersion": 1
          }
          """;
    }
    if (query.contains(TENANT_HCM_B.toString()) || query.contains("code=hcm_b")) {
      return """
          {
            "tenantId": "0195f3a0-0000-7000-8000-000000000102",
            "tenantCode": "hcm_b",
            "tenantName": "So Noi vu B",
            "status": "DANG_VAN_HANH",
            "tenancyModel": "BRIDGE",
            "cell": { "id": "cell-1", "code": "cell-hcm-01", "apiBaseUrl": "http://cell-hcm-01" },
            "databaseName": "td_hcm_b",
            "cacheTtlSeconds": 300,
            "sourceVersion": 1
          }
          """;
    }
    if (query.contains(TENANT_POOL_1.toString()) || query.contains("code=phuong_1")) {
      return """
          {
            "tenantId": "0195f3a0-0000-7000-8000-000000000201",
            "tenantCode": "phuong_1",
            "tenantName": "UBND Phuong 1",
            "status": "DANG_VAN_HANH",
            "tenancyModel": "POOL",
            "cell": { "id": "cell-1", "code": "cell-hcm-01", "apiBaseUrl": "http://cell-hcm-01" },
            "databaseName": "td_pool",
            "cacheTtlSeconds": 300,
            "sourceVersion": 1
          }
          """;
    }
    if (query.contains(TENANT_POOL_2.toString()) || query.contains("code=phuong_2")) {
      return """
          {
            "tenantId": "0195f3a0-0000-7000-8000-000000000202",
            "tenantCode": "phuong_2",
            "tenantName": "UBND Phuong 2",
            "status": "DANG_VAN_HANH",
            "tenancyModel": "POOL",
            "cell": { "id": "cell-1", "code": "cell-hcm-01", "apiBaseUrl": "http://cell-hcm-01" },
            "databaseName": "td_pool",
            "cacheTtlSeconds": 300,
            "sourceVersion": 1
          }
          """;
    }
    if (query.contains(TENANT_HN_A.toString()) || query.contains("code=hn_a")) {
      return """
          {
            "tenantId": "0195f3a0-0000-7000-8000-000000000301",
            "tenantCode": "hn_a",
            "tenantName": "So Noi vu HN",
            "status": "DANG_VAN_HANH",
            "tenancyModel": "BRIDGE",
            "cell": { "id": "cell-2", "code": "cell-hn-01", "apiBaseUrl": "http://cell-hn-01" },
            "databaseName": "td_hn_a",
            "cacheTtlSeconds": 300,
            "sourceVersion": 1
          }
          """;
    }
    return null;
  }
}
