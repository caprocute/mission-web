package vn.viettel.okrkpi.platform.tenanttest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Đọc và kiểm chứng cấu hình PgBouncer tham chiếu của NEN-033 ({@code
 * /pgbouncer/pgbouncer.ini.template} trong {@code vt-db-migration}) để dựng container kiểm thử Lớp
 * 3 ({@code default_pool_size = 1}, {@code pool_mode = transaction}) theo TL-03 mục "Kết nối và
 * PgBouncer" và "Kiểm thử rò rỉ tenant".
 */
public final class PgBouncerReferenceConfig {

  public static final String TEMPLATE_RESOURCE = "/pgbouncer/pgbouncer.ini.template";

  private final String rawTemplate;
  private final Map<String, String> pgbouncerSection;

  private PgBouncerReferenceConfig(String rawTemplate, Map<String, String> pgbouncerSection) {
    this.rawTemplate = rawTemplate;
    this.pgbouncerSection = Collections.unmodifiableMap(new LinkedHashMap<>(pgbouncerSection));
  }

  /**
   * Nạp và kiểm chứng {@code /pgbouncer/pgbouncer.ini.template} từ classpath của {@code
   * vt-db-migration}.
   */
  public static PgBouncerReferenceConfig loadAndValidate() {
    String raw = readTemplateResource();
    Map<String, String> section = parsePgbouncerSection(raw);
    requireSetting(section, "pool_mode", "transaction");
    requireSetting(section, "max_prepared_statements", "200");
    requireSetting(section, "server_reset_query", "");
    requireSetting(section, "auth_type", "scram-sha-256");
    return new PgBouncerReferenceConfig(raw, section);
  }

  public String rawTemplate() {
    return rawTemplate;
  }

  public Map<String, String> pgbouncerSection() {
    return pgbouncerSection;
  }

  public String poolMode() {
    return pgbouncerSection.get("pool_mode");
  }

  public int maxPreparedStatements() {
    return Integer.parseInt(pgbouncerSection.get("max_prepared_statements"));
  }

  public String authType() {
    return pgbouncerSection.get("auth_type");
  }

  public int listenPort() {
    return Integer.parseInt(pgbouncerSection.getOrDefault("listen_port", "6432"));
  }

  /**
   * Sinh biến môi trường cho container {@code edoburu/pgbouncer:v1.23.1-p3} bám đúng thông số trong
   * {@code pgbouncer.ini.template} nhưng ép {@code default_pool_size = 1} để mọi giao dịch dùng lại
   * duy nhất một kết nối máy chủ PostgreSQL.
   */
  public Map<String, String> containerEnv(
      String dbHost,
      int dbPort,
      String dbName,
      String dbUser,
      String dbPassword,
      int defaultPoolSize) {
    Objects.requireNonNull(dbHost, "dbHost");
    Objects.requireNonNull(dbName, "dbName");
    Objects.requireNonNull(dbUser, "dbUser");
    Objects.requireNonNull(dbPassword, "dbPassword");
    if (defaultPoolSize < 1) {
      throw new IllegalArgumentException("defaultPoolSize phải >= 1");
    }
    Map<String, String> env = new LinkedHashMap<>();
    env.put("DB_HOST", dbHost);
    env.put("DB_PORT", Integer.toString(dbPort));
    env.put("DB_NAME", dbName);
    env.put("DB_USER", dbUser);
    env.put("DB_PASSWORD", dbPassword);
    env.put("POOL_MODE", poolMode());
    env.put("MAX_PREPARED_STATEMENTS", Integer.toString(maxPreparedStatements()));
    env.put("SERVER_RESET_QUERY", pgbouncerSection.getOrDefault("server_reset_query", ""));
    env.put("SERVER_CHECK_QUERY", pgbouncerSection.getOrDefault("server_check_query", "select 1"));
    env.put("AUTH_TYPE", authType());
    env.put("DEFAULT_POOL_SIZE", Integer.toString(defaultPoolSize));
    env.put("MAX_CLIENT_CONN", pgbouncerSection.getOrDefault("max_client_conn", "4000"));
    env.put("LISTEN_PORT", Integer.toString(listenPort()));
    return Collections.unmodifiableMap(env);
  }

  private static String readTemplateResource() {
    try (InputStream in = PgBouncerReferenceConfig.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Không tìm thấy tài nguyên " + TEMPLATE_RESOURCE);
      }
      StringBuilder sb = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line).append('\n');
        }
      }
      return sb.toString();
    } catch (IOException e) {
      throw new IllegalStateException("Lỗi đọc " + TEMPLATE_RESOURCE, e);
    }
  }

  private static Map<String, String> parsePgbouncerSection(String raw) {
    Map<String, String> map = new LinkedHashMap<>();
    boolean inPgbouncer = false;
    for (String rawLine : raw.split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith(";;") || line.startsWith(";")) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        inPgbouncer = "[pgbouncer]".equalsIgnoreCase(line);
        continue;
      }
      if (inPgbouncer) {
        int eq = line.indexOf('=');
        if (eq > 0) {
          String key = line.substring(0, eq).trim();
          String val = line.substring(eq + 1).trim();
          map.put(key, val);
        }
      }
    }
    return map;
  }

  private static void requireSetting(
      Map<String, String> section, String key, String expectedValue) {
    String actual = section.get(key);
    if (!Objects.equals(expectedValue, actual)) {
      throw new IllegalStateException(
          "Cấu hình PgBouncer tham chiếu lệch chuẩn tại khoá '"
              + key
              + "': mong đợi '"
              + expectedValue
              + "' nhưng thấy '"
              + actual
              + "'");
    }
  }
}
