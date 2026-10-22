package vn.viettel.okrkpi.config.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.domain.HolidayDay;
import vn.viettel.okrkpi.config.domain.NationalHolidays;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;

/**
 * DL-07: migration lặp nạp lịch nghỉ quốc gia vào {@code config.holiday_calendar} của tenant hệ
 * thống, chạy lại không tạo dòng trùng, và RLS không mở rộng: tenant thường không thấy dòng của
 * tenant hệ thống (tenant thường dùng lịch dựng sẵn {@link NationalHolidays}, CORE-08-04).
 */
class NationalHolidaySeedIT {

  private static final UUID SYSTEM = new UUID(0, 0);
  private static final UUID TENANT_A = UUID.fromString("01927a08-0000-7000-8000-00000000a001");
  private static final PostgreSQLContainer<?> PG = MissionCoreTestDatabase.container();

  @Test
  void seedLoadsOncePerDateAndStaysInSystemTenant() throws SQLException {
    List<HolidayDay> expected = NationalHolidays.all();
    assertThat(rows(SYSTEM)).containsExactlyInAnyOrderElementsOf(ids(expected));

    int executed =
        new SchemaMigrator(MigrationOptions.defaults())
            .flyway(
                PG.getJdbcUrl(),
                "config",
                new MigrationCredentials.Credentials(
                    "mig_config", MissionCoreTestDatabase.TEST_DB_PASSWORD))
            .migrate()
            .migrationsExecuted;
    assertThat(executed).isZero();
    assertThat(rows(SYSTEM)).hasSize(expected.size());

    assertThat(rows(TENANT_A)).isEmpty();
  }

  private static List<String> ids(List<HolidayDay> days) {
    return days.stream().map(d -> d.id() + "|" + d.date() + "|" + d.kind()).toList();
  }

  private static List<String> rows(UUID tenant) throws SQLException {
    try (Connection c =
        DriverManager.getConnection(
            PG.getJdbcUrl(), "svc_config", MissionCoreTestDatabase.TEST_DB_PASSWORD)) {
      c.setAutoCommit(false);
      try (PreparedStatement st =
          c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        st.setString(1, tenant.toString());
        st.execute();
      }
      List<String> out = new ArrayList<>();
      try (PreparedStatement st =
              c.prepareStatement(
                  "SELECT id, calendar_date, day_kind FROM config.holiday_calendar"
                      + " WHERE scope = 'QUOC_GIA'");
          ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getObject(1) + "|" + rs.getDate(2).toLocalDate() + "|" + rs.getString(3));
        }
      }
      c.rollback();
      return out;
    }
  }
}
