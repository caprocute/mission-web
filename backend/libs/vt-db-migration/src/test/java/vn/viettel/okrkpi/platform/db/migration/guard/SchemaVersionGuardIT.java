package vn.viettel.okrkpi.platform.db.migration.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.MigrationConventionsTest;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;

/** Ứng dụng dừng khởi động khi lược đồ thấp hơn phiên bản tối thiểu (TL-03 L183). */
class SchemaVersionGuardIT {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(MigrationConventionsTest.POSTGRES_IMAGE);

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
  static void prepare() {
    POSTGRES.start();
    new SchemaMigrator(MigrationOptions.defaults().withCreateSchemas())
        .migrate(
            POSTGRES.getJdbcUrl(),
            "runner_ok",
            new MigrationCredentials.Credentials(POSTGRES.getUsername(), POSTGRES.getPassword()));
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
