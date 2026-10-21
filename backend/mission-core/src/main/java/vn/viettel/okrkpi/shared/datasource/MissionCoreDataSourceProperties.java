package vn.viettel.okrkpi.shared.datasource;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thuộc tính cấu hình multi-DataSource và kiểm tra phiên bản lược đồ của {@code SVC-05
 * mission-core} dưới tiền tố {@code okrkpi.*} (TL-01 L760–894).
 */
@ConfigurationProperties(prefix = "okrkpi")
public class MissionCoreDataSourceProperties {

  private Map<String, ModulePoolProperties> datasources = new LinkedHashMap<>();
  private SchemaGuardConfig schemaGuard = new SchemaGuardConfig();

  public Map<String, ModulePoolProperties> getDatasources() {
    return datasources;
  }

  public void setDatasources(Map<String, ModulePoolProperties> datasources) {
    this.datasources = datasources != null ? datasources : new LinkedHashMap<>();
  }

  public SchemaGuardConfig getSchemaGuard() {
    return schemaGuard;
  }

  public void setSchemaGuard(SchemaGuardConfig schemaGuard) {
    this.schemaGuard = schemaGuard != null ? schemaGuard : new SchemaGuardConfig();
  }

  /** Lấy cấu hình pool của một mô-đun nghiệp vụ, áp giá trị mặc định nếu chưa khai báo. */
  public ModulePoolProperties poolFor(ModuleName module) {
    ModulePoolProperties configured = datasources.get(module.schema());
    if (configured == null) {
      configured = new ModulePoolProperties();
    }
    if (configured.getMaxPoolSize() == null) {
      configured.setMaxPoolSize(module.defaultMaxPoolSize());
    }
    if (configured.getMinIdle() == null) {
      configured.setMinIdle(module.defaultMinIdle());
    }
    return configured;
  }

  /** Thông số HikariCP và read-replica cho từng mô-đun. */
  public static class ModulePoolProperties {

    private String url;
    private String username;
    private String password = "";
    private Integer maxPoolSize;
    private Integer minIdle;
    private long connectionTimeout = 2_000L;
    private long maxLifetime = 1_500_000L;
    private long idleTimeout = 300_000L;
    private long leakDetectionThreshold = 20_000L;
    private ReadReplicaProperties readReplica = new ReadReplicaProperties();

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public Integer getMaxPoolSize() {
      return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
    }

    public Integer getMinIdle() {
      return minIdle;
    }

    public void setMinIdle(Integer minIdle) {
      this.minIdle = minIdle;
    }

    public long getConnectionTimeout() {
      return connectionTimeout;
    }

    public void setConnectionTimeout(long connectionTimeout) {
      this.connectionTimeout = connectionTimeout;
    }

    public long getMaxLifetime() {
      return maxLifetime;
    }

    public void setMaxLifetime(long maxLifetime) {
      this.maxLifetime = maxLifetime;
    }

    public long getIdleTimeout() {
      return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout) {
      this.idleTimeout = idleTimeout;
    }

    public long getLeakDetectionThreshold() {
      return leakDetectionThreshold;
    }

    public void setLeakDetectionThreshold(long leakDetectionThreshold) {
      this.leakDetectionThreshold = leakDetectionThreshold;
    }

    public ReadReplicaProperties getReadReplica() {
      return readReplica;
    }

    public void setReadReplica(ReadReplicaProperties readReplica) {
      this.readReplica = readReplica != null ? readReplica : new ReadReplicaProperties();
    }
  }

  /** Thông số pool bản sao chỉ đọc (RO). */
  public static class ReadReplicaProperties {

    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private Integer maxPoolSize;
    private Integer minIdle;
    private long maxLagMs = RecentWriteTracker.DEFAULT_MAX_REPLICA_LAG_MS;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public Integer getMaxPoolSize() {
      return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
    }

    public Integer getMinIdle() {
      return minIdle;
    }

    public void setMinIdle(Integer minIdle) {
      this.minIdle = minIdle;
    }

    public long getMaxLagMs() {
      return maxLagMs;
    }

    public void setMaxLagMs(long maxLagMs) {
      this.maxLagMs = maxLagMs;
    }
  }

  /** Cấu hình đối chiếu phiên bản lược đồ khi khởi động cho 9 schema. */
  public static class SchemaGuardConfig {

    private boolean enabled = true;
    private String defaultMinVersion = "0.20";
    private Map<String, String> minVersions = new LinkedHashMap<>();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getDefaultMinVersion() {
      return defaultMinVersion;
    }

    public void setDefaultMinVersion(String defaultMinVersion) {
      this.defaultMinVersion = defaultMinVersion;
    }

    public Map<String, String> getMinVersions() {
      return minVersions;
    }

    public void setMinVersions(Map<String, String> minVersions) {
      this.minVersions = minVersions != null ? minVersions : new LinkedHashMap<>();
    }

    public String minVersionFor(String schema) {
      return minVersions.getOrDefault(schema, defaultMinVersion);
    }
  }
}
