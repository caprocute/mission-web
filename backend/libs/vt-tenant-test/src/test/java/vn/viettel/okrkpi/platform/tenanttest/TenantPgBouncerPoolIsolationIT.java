package vn.viettel.okrkpi.platform.tenanttest;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantJpaTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * NEN-034 Lớp 3 – Kiểm thử chống rò rỉ ngữ cảnh tenant dưới PgBouncer {@code pool_mode =
 * transaction} với {@code default_pool_size = 1} (bám cấu hình tham chiếu NEN-033 {@code
 * pgbouncer.ini.template}).
 *
 * <p>Xen kẽ tối thiểu 5.000 giao dịch của hai tenant ({@code TENANT_A}, {@code TENANT_B}) và giao
 * dịch không đặt ngữ cảnh qua cả {@link TenantJpaTransactionManager} lẫn {@link JdbcClient} ({@link
 * TenantDataSourceTransactionManager}), dùng chung duy nhất một kết nối máy chủ PostgreSQL ({@code
 * pg_backend_pid()}). Chứng minh:
 *
 * <ul>
 *   <li>Không giao dịch nào của A nhìn thấy dòng của B và ngược lại trên bảng có RLS thật ({@code
 *       task.inbox_processed}).
 *   <li>Giao dịch không đặt tenant luôn đọc được {@code current_setting('app.tenant_id', true) =
 *       ''} và 0 dòng dữ liệu (không rò giá trị từ giao dịch liền trước trên cùng kết nối máy chủ).
 *   <li>Mọi giao dịch đều đi qua đúng 1 {@code pg_backend_pid()} duy nhất của PostgreSQL.
 * </ul>
 */
class TenantPgBouncerPoolIsolationIT {

  private static final Logger log = LoggerFactory.getLogger(TenantPgBouncerPoolIsolationIT.class);

  private static final String DB_NAME = "td_pgbouncer";
  private static final String ROLE_PASSWORD = "nen034-pgbouncer-test-pwd";
  private static final int TOTAL_TRANSACTIONS = 5_200;

  private static final TenantId TENANT_A =
      TenantId.of(UUID.fromString("01927a00-0000-7000-8000-00000000000a"));
  private static final TenantId TENANT_B =
      TenantId.of(UUID.fromString("01927a00-0000-7000-8000-00000000000b"));

  private static final UUID EVENT_A1 = UUID.fromString("01927a00-0000-7000-8000-000000000101");
  private static final UUID EVENT_A2 = UUID.fromString("01927a00-0000-7000-8000-000000000102");
  private static final UUID EVENT_B1 = UUID.fromString("01927a00-0000-7000-8000-000000000201");

  private static final Network NETWORK = Network.newNetwork();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      TestPostgres.container(DB_NAME).withNetwork(NETWORK).withNetworkAliases("pg");

  private static GenericContainer<?> pgbouncer;
  private static HikariDataSource bouncerDataSource;
  private static LocalContainerEntityManagerFactoryBean emfBean;
  private static EntityManagerFactory emf;

  @BeforeAll
  static void setUpCluster() throws Exception {
    PgBouncerReferenceConfig refConfig = PgBouncerReferenceConfig.loadAndValidate();
    assertThat(refConfig.poolMode()).isEqualTo("transaction");
    assertThat(refConfig.maxPreparedStatements()).isEqualTo(200);
    assertThat(refConfig.authType()).isEqualTo("scram-sha-256");

    POSTGRES.start();

    Map<String, String> bootstrapEnv = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      bootstrapEnv.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", ROLE_PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), bootstrapEnv)
          .apply(admin, DatabaseLayout.TENANT_DATA);
    }

    SchemaMigrator migrator = new SchemaMigrator(MigrationOptions.defaults());
    for (String schema : List.of("keyring", "modulith", "task")) {
      migrator.migrate(
          POSTGRES.getJdbcUrl(),
          schema,
          new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), ROLE_PASSWORD));
    }

    seedInitialTenantRows();

    Map<String, String> bouncerEnv =
        refConfig.containerEnv("pg", 5432, DB_NAME, "svc_task", ROLE_PASSWORD, 1);

    pgbouncer =
        new GenericContainer<>(
                DockerImageName.parse("mirror.gcr.io/edoburu/pgbouncer:v1.23.1-p3")
                    .asCompatibleSubstituteFor("edoburu/pgbouncer"))
            .withNetwork(NETWORK)
            .withEnv(bouncerEnv)
            .withExposedPorts(refConfig.listenPort())
            .waitingFor(Wait.forListeningPort());
    pgbouncer.start();

    HikariConfig hikari = new HikariConfig();
    hikari.setDriverClassName(org.postgresql.Driver.class.getName());
    hikari.setJdbcUrl(
        "jdbc:postgresql://%s:%d/%s?prepareThreshold=1"
            .formatted(
                pgbouncer.getHost(), pgbouncer.getMappedPort(refConfig.listenPort()), DB_NAME));
    hikari.setUsername("svc_task");
    hikari.setPassword(ROLE_PASSWORD);
    hikari.setMaximumPoolSize(8);
    hikari.setMinimumIdle(4);
    bouncerDataSource = new HikariDataSource(hikari);

    emfBean = new LocalContainerEntityManagerFactoryBean();
    emfBean.setDataSource(bouncerDataSource);
    emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    emfBean.setPackagesToScan("vn.viettel.okrkpi.platform.tenanttest.noentities");
    emfBean.setPersistenceUnitName("nen034-pgbouncer");
    emfBean.getJpaPropertyMap().put("hibernate.boot.allow_jdbc_metadata_access", "false");
    emfBean.getJpaPropertyMap().put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    emfBean.afterPropertiesSet();
    emf = emfBean.getObject();
  }

  @AfterAll
  static void tearDownCluster() {
    if (emfBean != null) {
      emfBean.destroy();
    }
    if (bouncerDataSource != null) {
      bouncerDataSource.close();
    }
    if (pgbouncer != null) {
      pgbouncer.stop();
    }
    POSTGRES.stop();
    NETWORK.close();
  }

  @Test
  void xenKe5200GiaoDichHaiTenantQuaMotKetNoiPgBouncerKhongRoRi() throws Exception {
    TenantDataSourceTransactionManager jdbcTm =
        new TenantDataSourceTransactionManager(bouncerDataSource, TenantProperties.defaults());
    TransactionTemplate jdbcTx = new TransactionTemplate(jdbcTm);
    JdbcClient jdbc = JdbcClient.create(bouncerDataSource);

    TenantJpaTransactionManager jpaTm =
        new TenantJpaTransactionManager(emf, TenantProperties.defaults());
    TransactionTemplate jpaTx = new TransactionTemplate(jpaTm);

    Set<Integer> backendPids = ConcurrentHashMap.newKeySet();
    AtomicInteger completedCount = new AtomicInteger();

    Instant start = Instant.now();

    // Pha 1: 2.600 giao dịch tuần tự xen kẽ chặt (A -> B -> không tenant -> B -> A -> không tenant)
    // qua cả JdbcClient và JPA để bắt ngay lập tức mọi rò rỉ trạng thái từ giao dịch liền trước.
    int sequentialCount = 2_600;
    for (int i = 0; i < sequentialCount; i++) {
      boolean useJpa = (i & 1) == 1;
      int mod = i % 5;
      if (mod == 0 || mod == 3) {
        verifyTenantTransaction(
            TENANT_A, List.of(EVENT_A1, EVENT_A2), useJpa, jdbcTx, jdbc, jpaTx, backendPids);
      } else if (mod == 1 || mod == 2) {
        verifyTenantTransaction(
            TENANT_B, List.of(EVENT_B1), useJpa, jdbcTx, jdbc, jpaTx, backendPids);
      } else {
        verifyNoTenantTransaction(useJpa, jdbcTx, jdbc, jpaTx, backendPids);
      }
      completedCount.incrementAndGet();
    }

    // Pha 2: 2.600 giao dịch đồng thời từ 8 luồng khách cùng tranh 1 kết nối máy chủ duy nhất
    // (default_pool_size = 1) qua PgBouncer.
    int concurrentCount = TOTAL_TRANSACTIONS - sequentialCount;
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Void>> tasks =
          java.util.stream.IntStream.range(0, concurrentCount)
              .<Callable<Void>>mapToObj(
                  i ->
                      () -> {
                        boolean useJpa = (i & 1) == 0;
                        int mod = i % 5;
                        if (mod == 0 || mod == 4) {
                          verifyTenantTransaction(
                              TENANT_A,
                              List.of(EVENT_A1, EVENT_A2),
                              useJpa,
                              jdbcTx,
                              jdbc,
                              jpaTx,
                              backendPids);
                        } else if (mod == 1 || mod == 3) {
                          verifyTenantTransaction(
                              TENANT_B,
                              List.of(EVENT_B1),
                              useJpa,
                              jdbcTx,
                              jdbc,
                              jpaTx,
                              backendPids);
                        } else {
                          verifyNoTenantTransaction(useJpa, jdbcTx, jdbc, jpaTx, backendPids);
                        }
                        completedCount.incrementAndGet();
                        return null;
                      })
              .toList();

      for (Future<Void> f : pool.invokeAll(tasks)) {
        f.get();
      }
    }

    Duration elapsed = Duration.between(start, Instant.now());
    log.info(
        "NEN-034 Lớp 3 hoàn tất: {} giao dịch xen kẽ (JDBC + JPA) qua PgBouncer"
            + " (default_pool_size=1, backendPids={}) trong {} ms",
        completedCount.get(),
        backendPids,
        elapsed.toMillis());

    assertThat(completedCount.get()).isGreaterThanOrEqualTo(5_000);
    // default_pool_size = 1: mọi giao dịch của cả hai tenant và không-tenant đều dùng chung đúng 1
    // kết nối máy chủ PostgreSQL duy nhất.
    assertThat(backendPids)
        .as("PgBouncer default_pool_size=1 chỉ được mở duy nhất 1 kết nối máy chủ PostgreSQL")
        .hasSize(1);
  }

  private static void verifyTenantTransaction(
      TenantId tenant,
      List<UUID> expectedEvents,
      boolean useJpa,
      TransactionTemplate jdbcTx,
      JdbcClient jdbc,
      TransactionTemplate jpaTx,
      Set<Integer> backendPids)
      throws Exception {
    Observation obs =
        TenantContext.call(
            tenant,
            () ->
                useJpa
                    ? jpaTx.execute(status -> observeViaJpa(emf))
                    : jdbcTx.execute(status -> observeViaJdbc(jdbc)));

    assertThat(obs).isNotNull();
    backendPids.add(obs.backendPid());
    assertThat(obs.tenantSetting()).isEqualTo(tenant.toString());
    assertThat(obs.eventIds()).containsExactlyElementsOf(expectedEvents);
  }

  private static void verifyNoTenantTransaction(
      boolean useJpa,
      TransactionTemplate jdbcTx,
      JdbcClient jdbc,
      TransactionTemplate jpaTx,
      Set<Integer> backendPids) {
    Observation obs =
        useJpa
            ? jpaTx.execute(status -> observeViaJpa(emf))
            : jdbcTx.execute(status -> observeViaJdbc(jdbc));

    assertThat(obs).isNotNull();
    backendPids.add(obs.backendPid());
    assertThat(obs.tenantSetting()).isEmpty();
    assertThat(obs.eventIds()).isEmpty();
  }

  private static Observation observeViaJdbc(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT pg_backend_pid() AS pid,
                   coalesce(current_setting('app.tenant_id', true), '') AS setting,
                   coalesce(
                     (SELECT string_agg(event_id::text, ',' ORDER BY event_id)
                        FROM task.inbox_processed),
                     '') AS events
            """)
        .query(
            (rs, rowNum) ->
                new Observation(
                    rs.getInt("pid"),
                    rs.getString("setting"),
                    parseUuidCsv(rs.getString("events"))))
        .single();
  }

  private static Observation observeViaJpa(EntityManagerFactory emf) {
    EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
    Object[] row =
        (Object[])
            em.createNativeQuery(
                    """
                    SELECT pg_backend_pid() AS pid,
                           coalesce(current_setting('app.tenant_id', true), '') AS setting,
                           coalesce(
                             (SELECT string_agg(event_id::text, ',' ORDER BY event_id)
                                FROM task.inbox_processed),
                             '') AS events
                    """)
                .getSingleResult();
    int pid = ((Number) row[0]).intValue();
    String setting = (String) row[1];
    return new Observation(pid, setting, parseUuidCsv((String) row[2]));
  }

  private static List<UUID> parseUuidCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    String[] parts = csv.split(",");
    List<UUID> result = new java.util.ArrayList<>(parts.length);
    for (String part : parts) {
      result.add(UUID.fromString(part.trim()));
    }
    return result;
  }

  private static void seedInitialTenantRows() throws SQLException {
    try (Connection conn =
        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "svc_task", ROLE_PASSWORD)) {
      conn.setAutoCommit(false);
      insertInboxRow(conn, TENANT_A.value(), EVENT_A1, 1L);
      insertInboxRow(conn, TENANT_A.value(), EVENT_A2, 2L);
      insertInboxRow(conn, TENANT_B.value(), EVENT_B1, 1L);
      conn.commit();
    }
  }

  private static void insertInboxRow(Connection conn, UUID tenantId, UUID eventId, long offset)
      throws SQLException {
    try (PreparedStatement setCfg =
        conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      setCfg.setString(1, tenantId.toString());
      setCfg.execute();
    }
    try (PreparedStatement ins =
        conn.prepareStatement(
            "INSERT INTO task.inbox_processed (event_id, consumer, processed_at, tenant_id,"
                + " source_topic, kafka_partition, kafka_offset)"
                + " VALUES (?, 'nen034-pgbouncer-probe', now(), ?, 'task.task.assigned.v1', 0, ?)")) {
      ins.setObject(1, eventId);
      ins.setObject(2, tenantId);
      ins.setLong(3, offset);
      ins.executeUpdate();
    }
  }

  private record Observation(int backendPid, String tenantSetting, List<UUID> eventIds) {}
}
