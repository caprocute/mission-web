package vn.viettel.okrkpi.platform.db.migration.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;

/** Mỗi bộ migration mẫu sai làm đúng một luật lint thất bại, thông báo chỉ rõ đối tượng. */
class ConventionLinterIT {

  private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "upper, IDENTIFIER_LOWERCASE, bad_upper.SampleRow",
    "pk,    NAMING_PRIMARY_KEY,   bad_pk.sample_row ràng buộc sample_row_pkey",
    "fk,    NAMING_FOREIGN_KEY,   bad_fk.sample_row ràng buộc sample_row_parent_fk",
    "uq,    NAMING_UNIQUE,        bad_uq.sample_row ràng buộc sample_row_code_key",
    "ck,    NAMING_CHECK,         bad_ck.sample_row ràng buộc status_check",
    "ex,    NAMING_EXCLUSION,     bad_ex.sample_row ràng buộc no_overlap",
    "ix,    NAMING_INDEX,         bad_ix.sample_row chỉ mục idx_tenant",
    "rls,   NAMING_POLICY,        bad_rls.sample_row chính sách tenant_isolation",
    "tg,    NAMING_TRIGGER,       bad_tg.sample_row trigger before_update",
    "fn,    NAMING_FUNCTION,      bad_fn.create_partitions()",
    "type,  TYPE_STANDARD,        bad_type.sample_row.name",
    "flag,  FLAG_COLUMN,          bad_flag.sample_row.is_active",
    "ts,    TIMESTAMP_COLUMN,     bad_ts.sample_row.locked_at",
    "cls,   CLASSIFICATION_COMMENT, bad_cls.sample_row.code",
    "c2,    CLASSIFICATION_C2_ENCRYPTED, bad_c2.sample_row.comment",
  })
  void mauSaiBiBatDungLuat(String sample, LintRule rule, String object) throws SQLException {
    String schema = "bad_" + sample;
    new SchemaMigrator(new MigrationOptions("classpath:db/none", "classpath:db/test-bad/", true))
        .migrate(
            POSTGRES.getJdbcUrl(),
            schema,
            new MigrationCredentials.Credentials(POSTGRES.getUsername(), POSTGRES.getPassword()));
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      List<Violation> violations = ConventionLinter.lint(c, schema);
      assertThat(violations)
          .as("vi phạm của mẫu %s", sample)
          .anySatisfy(
              v -> {
                assertThat(v.rule()).isEqualTo(rule);
                assertThat(v.object()).isEqualTo(object);
                assertThat(v.message()).isNotBlank();
              });
      assertThat(violations)
          .as("mẫu %s chỉ được sai đúng luật %s", sample, rule)
          .allSatisfy(v -> assertThat(v.rule()).isIn(rule, LintRule.IDENTIFIER_LOWERCASE));
    }
  }
}
