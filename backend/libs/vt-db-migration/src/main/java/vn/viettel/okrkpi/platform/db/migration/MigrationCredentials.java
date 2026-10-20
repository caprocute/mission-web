package vn.viettel.okrkpi.platform.db.migration;

/**
 * Nguồn thông tin đăng nhập cho tài khoản {@code mig_<schema>}. Bản mặc định là {@link
 * EnvironmentCredentials}; mật khẩu không bao giờ nằm trong mã hay cấu hình commit (ADR-17).
 */
@FunctionalInterface
public interface MigrationCredentials {

  /** Tên đăng nhập và mật khẩu dùng để chạy migration của {@code schema} trên {@code jdbcUrl}. */
  Credentials forSchema(String jdbcUrl, String schema);

  /** Cặp tên đăng nhập và mật khẩu. {@link #toString()} không in mật khẩu. */
  record Credentials(String user, String password) {
    @Override
    public String toString() {
      return "Credentials[user=" + user + "]";
    }
  }
}
