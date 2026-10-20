package vn.viettel.okrkpi.platform.db.migration.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/** Ứng dụng dừng khởi động khi lược đồ thấp hơn phiên bản tối thiểu (TL-03 L183). */
class SchemaVersionGuardIT {

  private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container();

  private static final String PWD = "mat-khau-thu-nghiem";

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class, SchemaVersionGuardAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword());
  }

  @BeforeAll
  static void prepare() throws java.sql.SQLException {
    POSTGRES.start();
    java.util.Map<String, String> env = new java.util.HashMap<>();
    for (String role : RoleBootstrapPlan.roles("runner_ok")) {
      env.put(role.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD", PWD);
    }
    try (java.sql.Connection admin =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env)
          .applySchemas(admin, java.util.List.of("runner_ok"));
    }
    new SchemaMigrator(MigrationOptions.defaults())
        .migrate(
            POSTGRES.getJdbcUrl(),
            "runner_ok",
            new MigrationCredentials.Credentials("mig_runner_ok", PWD));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void khoiDongKhiDatPhienBanToiThieu() {
    runner()
        .withPropertyValues(
            "vt.db.schema-guard.schemas[0].name=runner_ok",
            "vt.db.schema-guard.schemas[0].min-version=1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void dungKhoiDongKhiLuocDoThapHon() {
    runner()
        .withPropertyValues(
            "vt.db.schema-guard.schemas[0].name=runner_ok",
            "vt.db.schema-guard.schemas[0].min-version=1.2")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(SchemaVersionTooLowException.class)
                    .hasMessageContaining("runner_ok")
                    .hasMessageContaining("1.2"));
  }

  @Test
  void dungKhoiDongKhiChuaChayMigration() {
    runner()
        .withPropertyValues(
            "vt.db.schema-guard.schemas[0].name=chua_co",
            "vt.db.schema-guard.schemas[0].min-version=1")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("chưa chạy migration"));
  }

  @Test
  void tatKiemTraBangCauHinh() {
    runner()
        .withPropertyValues(
            "vt.db.schema-guard.enabled=false",
            "vt.db.schema-guard.schemas[0].name=chua_co",
            "vt.db.schema-guard.schemas[0].min-version=1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void docPhienBanHienTai() {
    assertThat(
            SchemaVersionGuard.currentVersion(
                    org.springframework.boot.jdbc.DataSourceBuilder.create()
                        .url(POSTGRES.getJdbcUrl())
                        .username(POSTGRES.getUsername())
                        .password(POSTGRES.getPassword())
                        .build(),
                    "runner_ok")
                .map(Object::toString))
        .contains("1");
  }
}
