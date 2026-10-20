package vn.viettel.okrkpi.platform.db.migration.guard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;

/**
 * Kiểm phiên bản lược đồ khi ứng dụng khởi động (TL-03 "Ứng dụng không có quyền DDL"): ứng dụng
 * không tự chạy migration mà đọc {@code <schema>.flyway_schema_history}; nếu phiên bản thấp hơn mức
 * tối thiểu mã yêu cầu thì dừng với thông báo rõ thay vì chạy sai.
 */
public final class SchemaVersionGuard {

  private SchemaVersionGuard() {}

  /**
   * Phiên bản cao nhất đã áp thành công của schema, rỗng khi chưa có bảng lịch sử hoặc chưa có
   * migration có phiên bản nào.
   */
  public static Optional<MigrationVersion> currentVersion(DataSource dataSource, String schema) {
    String sql =
        "select version from "
            + SchemaNames.require(schema)
            + "."
            + SchemaMigrator.HISTORY_TABLE
            + " where success and version is not null";
    try (Connection connection = dataSource.getConnection()) {
      if (!historyExists(connection, schema)) {
        return Optional.empty();
      }
      try (PreparedStatement statement = connection.prepareStatement(sql);
          ResultSet rows = statement.executeQuery()) {
        MigrationVersion max = null;
        while (rows.next()) {
          MigrationVersion v = MigrationVersion.fromVersion(rows.getString(1));
          if (max == null || v.compareTo(max) > 0) {
            max = v;
          }
        }
        return Optional.ofNullable(max);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Không đọc được lịch sử migration của schema " + schema, e);
    }
  }

  private static boolean historyExists(Connection connection, String schema) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select to_regclass(?) is not null")) {
      statement.setString(1, schema + "." + SchemaMigrator.HISTORY_TABLE);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1);
      }
    }
  }

  /**
   * Ném {@link SchemaVersionTooLowException} khi lược đồ thấp hơn {@code minimumVersion}.
   *
   * @return phiên bản hiện tại của schema
   */
  public static MigrationVersion verify(
      DataSource dataSource, String schema, String minimumVersion) {
    MigrationVersion required = MigrationVersion.fromVersion(minimumVersion);
    Optional<MigrationVersion> current = currentVersion(dataSource, schema);
    if (current.isEmpty() || current.get().compareTo(required) < 0) {
      throw new SchemaVersionTooLowException(
          schema, current.map(MigrationVersion::getVersion).orElse(null), required.getVersion());
    }
    return current.get();
  }
}
