package vn.viettel.okrkpi.platform.db.migration.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;

/** Giới hạn kết nối theo vai trò, đọc từ {@code db/bootstrap/role-limits.properties}. */
public final class RoleLimits {

  private static final String RESOURCE = "db/bootstrap/role-limits.properties";

  private final Properties properties;

  private RoleLimits(Properties properties) {
    this.properties = properties;
  }

  /** Nạp tệp tham số mặc định trong thư viện. */
  public static RoleLimits load() {
    Properties p = new Properties();
    try (InputStream in = RoleLimits.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu tệp " + RESOURCE);
      }
      p.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new RoleLimits(p);
  }

  /**
   * Giới hạn của vai trò {@code <kind>_<schema>}.
   *
   * @param kind một trong {@code mig}, {@code svc}, {@code ro}, {@code cdc}
   */
  public int limit(String kind, String schema) {
    String value = properties.getProperty(kind + "." + SchemaNames.require(schema));
    if (value == null) {
      value = properties.getProperty("default." + kind);
    }
    if (value == null) {
      throw new IllegalStateException("Thiếu giới hạn kết nối default." + kind);
    }
    return Integer.parseInt(value.strip());
  }
}
