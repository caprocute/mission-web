package vn.viettel.okrkpi.platform.testkit;

import java.util.Map;
import java.util.Objects;

/**
 * Hỗ trợ đọc động địa chỉ máy chủ và dải cổng {@code 35xxx} từ biến môi trường (hoặc thuộc tính hệ
 * thống) khi lập trình viên kết nối tới hạ tầng dev qua cổng hầm SSH (SSH tunnel).
 */
public final class MissionDevEnv {

  public static final String DEFAULT_HOST = "127.0.0.1";
  public static final int DEFAULT_POSTGRES_PORT = 35432;
  public static final int DEFAULT_PGBOUNCER_PORT = 35433;
  public static final int DEFAULT_VALKEY_PORT = 35379;
  public static final int DEFAULT_KEYCLOAK_PORT = 35080;
  public static final int DEFAULT_OPA_PORT = 35181;
  public static final int DEFAULT_OPENBAO_PORT = 35820;
  public static final int DEFAULT_KAFKA_EXTERNAL_PORT = 35094;
  public static final int DEFAULT_APICURIO_PORT = 35081;
  public static final int DEFAULT_DEBEZIUM_PORT = 35083;
  public static final int DEFAULT_OTEL_HTTP_PORT = 35318;

  private final Map<String, String> env;

  public MissionDevEnv() {
    this(System.getenv());
  }

  public MissionDevEnv(Map<String, String> env) {
    this.env = Map.copyOf(Objects.requireNonNull(env, "env must not be null"));
  }

  public String host() {
    return readString("DEV_HOST", readString("DB_HOST", DEFAULT_HOST));
  }

  public int postgresPort() {
    return readPort("POSTGRES_PORT", readPort("DB_PORT", DEFAULT_POSTGRES_PORT));
  }

  public int pgBouncerPort() {
    return readPort("PGBOUNCER_PORT", DEFAULT_PGBOUNCER_PORT);
  }

  public int valkeyPort() {
    return readPort("VALKEY_PORT", DEFAULT_VALKEY_PORT);
  }

  public int keycloakPort() {
    return readPort("KEYCLOAK_PORT", DEFAULT_KEYCLOAK_PORT);
  }

  public int opaPort() {
    return readPort("OPA_PORT", DEFAULT_OPA_PORT);
  }

  public int openBaoPort() {
    return readPort("OPENBAO_PORT", DEFAULT_OPENBAO_PORT);
  }

  public int kafkaExternalPort() {
    return readPort("KAFKA_EXTERNAL_PORT", DEFAULT_KAFKA_EXTERNAL_PORT);
  }

  public int apicurioPort() {
    return readPort("APICURIO_PORT", DEFAULT_APICURIO_PORT);
  }

  public int debeziumPort() {
    return readPort("DEBEZIUM_PORT", DEFAULT_DEBEZIUM_PORT);
  }

  public String directJdbcUrl(String database) {
    return "jdbc:postgresql://" + host() + ":" + postgresPort() + "/" + database;
  }

  public String pgBouncerJdbcUrl(String database) {
    return "jdbc:postgresql://" + host() + ":" + pgBouncerPort() + "/" + database;
  }

  public String opaBaseUrl() {
    return readString("OPA_URL", "http://" + host() + ":" + opaPort());
  }

  public String openBaoBaseUrl() {
    return readString("OPENBAO_URL", "http://" + host() + ":" + openBaoPort());
  }

  public String keycloakIssuerUri() {
    return readString(
        "KEYCLOAK_ISSUER_URI", "http://" + host() + ":" + keycloakPort() + "/realms/mission");
  }

  private String readString(String key, String defaultValue) {
    String val = env.get(key);
    if (val == null || val.isBlank()) {
      return defaultValue;
    }
    return val.trim();
  }

  private int readPort(String key, int defaultPort) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return defaultPort;
    }
    int parsed = Integer.parseInt(raw.trim());
    if (parsed < 1 || parsed > 65535) {
      throw new IllegalArgumentException("Invalid port in " + key + ": " + raw);
    }
    return parsed;
  }
}
