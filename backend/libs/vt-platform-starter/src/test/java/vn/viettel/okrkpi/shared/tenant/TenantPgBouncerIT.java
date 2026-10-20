package vn.viettel.okrkpi.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * NEN-010 mục 6: qua PgBouncer chế độ giao dịch với {@code default_pool_size = 1} (mọi giao dịch
 * dùng chung một kết nối máy chủ), tenant của giao dịch trước không rò sang giao dịch sau, và RLS
 * {@code FORCE} chỉ trả dòng của tenant đang đặt (TL-03 "Đặt ngữ cảnh tenant an toàn qua
 * PgBouncer").
 */
class TenantPgBouncerIT {

  private static final String APP_PASSWORD = "app-test-only";
  private static final TenantId A = TenantId.of(UUID.randomUUID());
  private static final TenantId B = TenantId.of(UUID.randomUUID());

  private static final Network NETWORK = Network.newNetwork();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
          .withNetwork(NETWORK)
          .withNetworkAliases("pg");

  @SuppressWarnings("resource")
  private static final GenericContainer<?> PGBOUNCER =
      new GenericContainer<>(
              DockerImageName.parse("mirror.gcr.io/edoburu/pgbouncer:v1.23.1-p3")
                  .asCompatibleSubstituteFor("edoburu/pgbouncer"))
          .withNetwork(NETWORK)
          .withEnv(
              Map.ofEntries(
                  Map.entry("MAX_PREPARED_STATEMENTS", "100"),
                  Map.entry("DB_HOST", "pg"),
                  Map.entry("DB_PORT", "5432"),
                  Map.entry("DB_NAME", "test"),
                  Map.entry("DB_USER", "app_user"),
                  Map.entry("DB_PASSWORD", APP_PASSWORD),
                  Map.entry("AUTH_TYPE", "scram-sha-256"),
                  Map.entry("POOL_MODE", "transaction"),
                  Map.entry("DEFAULT_POOL_SIZE", "1"),
                  Map.entry("MAX_CLIENT_CONN", "50"),
                  Map.entry("LISTEN_PORT", "6432")))
          .withExposedPorts(6432)
          .waitingFor(Wait.forListeningPort());

  private static SimpleDriverDataSource bouncer;

  @BeforeAll
  static void start() throws Exception {
    POSTGRES.start();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("create role app_user login password '" + APP_PASSWORD + "'");
      st.execute(
          """
          create table task_sample (
            id uuid primary key default gen_random_uuid(),
            tenant_id uuid not null default nullif(current_setting('app.tenant_id', true), '')::uuid,
            title text not null)
          """);
      st.execute("alter table task_sample enable row level security");
      st.execute("alter table task_sample force row level security");
      st.execute(
          """
          create policy rls_task_sample__tenant on task_sample
            using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
            with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
          """);
      st.execute("grant select, insert on task_sample to app_user");
      st.execute(
          "insert into task_sample(tenant_id, title) values ('%s', 'a1'), ('%s', 'a2'), ('%s', 'b1')"
              .formatted(A, A, B));
    }
    PGBOUNCER.start();
    bouncer = new SimpleDriverDataSource();
    bouncer.setDriverClass(org.postgresql.Driver.class);
    // prepareThreshold=1: mọi câu đều thành prepared statement phía máy chủ, kiểm cả
    // max_prepared_statements của PgBouncer 1.21+ (TL-03 giữ JDBC mặc định).
    bouncer.setUrl(
        "jdbc:postgresql://%s:%d/test?prepareThreshold=1"
            .formatted(PGBOUNCER.getHost(), PGBOUNCER.getMappedPort(6432)));
    bouncer.setUsername("app_user");
    bouncer.setPassword(APP_PASSWORD);
  }

  @AfterAll
  static void stop() {
    PGBOUNCER.stop();
    POSTGRES.stop();
    NETWORK.close();
  }

  private record Seen(int backendPid, String setting, List<String> titles) {}

  private static Seen observe(JdbcClient jdbc) {
    int pid = jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
    String setting =
        jdbc.sql("select coalesce(current_setting('app.tenant_id', true), '')")
            .query(String.class)
            .single();
    List<String> titles =
        jdbc.sql("select title from task_sample order by title").query(String.class).list();
    return new Seen(pid, setting, titles);
  }

  @Test
  void jdbcTenantKhongRoQuaKetNoiMayChuDungChung() throws Exception {
    TenantDataSourceTransactionManager tm =
        new TenantDataSourceTransactionManager(bouncer, TenantProperties.defaults());
    TransactionTemplate tx = new TransactionTemplate(tm);
    JdbcClient jdbc = JdbcClient.create(bouncer);

    Seen a = TenantContext.call(A, () -> tx.execute(s -> observe(jdbc)));
    Seen b = TenantContext.call(B, () -> tx.execute(s -> observe(jdbc)));
    Seen none = tx.execute(s -> observe(jdbc));
    Seen outsideTx = observe(jdbc);

    assertThat(a.titles()).containsExactly("a1", "a2");
    assertThat(a.setting()).isEqualTo(A.toString());
    assertThat(b.backendPid()).isEqualTo(a.backendPid());
    assertThat(b.titles()).containsExactly("b1");
    assertThat(b.setting()).isEqualTo(B.toString());
    assertThat(none.backendPid()).isEqualTo(a.backendPid());
    assertThat(none.setting()).isEmpty();
    assertThat(none.titles()).isEmpty();
    // Ngoài giao dịch Spring (autocommit) cũng không còn tenant nào trên kết nối máy chủ.
    assertThat(outsideTx.backendPid()).isEqualTo(a.backendPid());
    assertThat(outsideTx.setting()).isEmpty();
    assertThat(outsideTx.titles()).isEmpty();
  }

  @Test
  void jdbcGhiDongMoiNhanTenantCuaGiaoDich() throws Exception {
    TransactionTemplate tx =
        new TransactionTemplate(
            new TenantDataSourceTransactionManager(bouncer, TenantProperties.defaults()));
    JdbcClient jdbc = JdbcClient.create(bouncer);
    TenantId c = TenantId.of(UUID.randomUUID());
    TenantContext.run(
        c,
        () ->
            tx.executeWithoutResult(
                s -> jdbc.sql("insert into task_sample(title) values ('c1')").update()));
    List<String> written = TenantContext.call(c, () -> tx.execute(s -> observe(jdbc).titles()));
    assertThat(written).containsExactly("c1");
    // Ghi dòng cho tenant khác tenant đang đặt bị WITH CHECK chặn.
    assertThatThrownBy(
            () ->
                TenantContext.run(
                    c,
                    () ->
                        tx.executeWithoutResult(
                            s ->
                                jdbc.sql(
                                        "insert into task_sample(tenant_id, title) values (?::uuid, 'x')")
                                    .param(A.toString())
                                    .update())))
        .rootCause()
        .hasMessageContaining("row-level security");
  }

  @Test
  void jpaTenantKhongRoQuaKetNoiMayChuDungChung() throws Exception {
    LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(bouncer);
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setPackagesToScan("vn.viettel.okrkpi.shared.tenant.nojpaentities");
    factory.setPersistenceUnitName("nen010");
    factory.getJpaPropertyMap().put("hibernate.boot.allow_jdbc_metadata_access", "false");
    factory.getJpaPropertyMap().put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    factory.afterPropertiesSet();
    EntityManagerFactory emf = factory.getObject();
    try {
      TenantJpaTransactionManager tm =
          new TenantJpaTransactionManager(emf, TenantProperties.defaults());
      TransactionTemplate tx = new TransactionTemplate(tm);

      Seen a = TenantContext.call(A, () -> tx.execute(s -> observeJpa(emf)));
      Seen b = TenantContext.call(B, () -> tx.execute(s -> observeJpa(emf)));
      Seen none = tx.execute(s -> observeJpa(emf));

      assertThat(a.titles()).containsExactly("a1", "a2");
      assertThat(b.backendPid()).isEqualTo(a.backendPid());
      assertThat(b.titles()).containsExactly("b1");
      assertThat(none.backendPid()).isEqualTo(a.backendPid());
      assertThat(none.setting()).isEmpty();
      assertThat(none.titles()).isEmpty();
    } finally {
      factory.destroy();
    }
  }

  @SuppressWarnings("unchecked")
  private static Seen observeJpa(EntityManagerFactory emf) {
    EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
    int pid =
        ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
    String setting =
        (String)
            em.createNativeQuery("select coalesce(current_setting('app.tenant_id', true), '')")
                .getSingleResult();
    List<String> titles =
        em.createNativeQuery("select title from task_sample order by title").getResultList();
    return new Seen(pid, setting, titles);
  }

  @Test
  void tuChoiDatTenantKhiAutocommit() throws Exception {
    try (Connection c = bouncer.getConnection()) {
      assertThatThrownBy(() -> TenantSql.apply(c, TenantSql.DEFAULT_SETTING))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
