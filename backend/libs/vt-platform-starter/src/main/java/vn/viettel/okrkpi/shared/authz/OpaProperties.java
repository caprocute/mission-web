package vn.viettel.okrkpi.shared.authz;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.viettel.okrkpi.shared.resilience.PlatformTimeouts;

/**
 * Cấu hình kết nối OPA cùng pod, bộ đệm quyết định và cửa sổ xác thực bước lên AAL2 (NEN-014,
 * ADR-21, TL-01 "Uỷ quyền tập trung bằng ABAC").
 */
@ConfigurationProperties(prefix = "okrkpi.opa")
public class OpaProperties {

  private boolean enabled = true;
  private URI baseUrl = URI.create("http://127.0.0.1:8181");
  private String decisionPath = "/v1/data/okrkpi/authz/decision";
  private String compilePath = "/v1/compile";
  private Duration connectTimeout = PlatformTimeouts.OPA_CONNECT;
  private Duration readTimeout = PlatformTimeouts.OPA_READ;
  private Duration decisionCacheTtl = Duration.ofSeconds(30);
  private Duration sqlFilterCacheTtl = Duration.ofSeconds(60);
  private int maxCacheEntries = 10_000;

  /**
   * Cửa sổ hiệu lực {@code auth_time} cho thao tác AAL2. Mặc định 5 phút (300 giây) theo hợp đồng
   * {@code common.yaml} và TL-01; cho phép ghi đè theo tenant (ví dụ 15 phút theo BR-00-02 / {@code
   * mfa_stepup_window_minutes}).
   */
  private Duration stepUpWindow = Duration.ofMinutes(5);

  private Map<String, Duration> tenantStepUpWindows = new LinkedHashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public URI getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(URI baseUrl) {
    this.baseUrl = baseUrl == null ? URI.create("http://127.0.0.1:8181") : baseUrl;
  }

  public String getDecisionPath() {
    return decisionPath;
  }

  public void setDecisionPath(String decisionPath) {
    this.decisionPath =
        decisionPath == null || decisionPath.isBlank()
            ? "/v1/data/okrkpi/authz/decision"
            : decisionPath;
  }

  public String getCompilePath() {
    return compilePath;
  }

  public void setCompilePath(String compilePath) {
    this.compilePath = compilePath == null || compilePath.isBlank() ? "/v1/compile" : compilePath;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout == null ? PlatformTimeouts.OPA_CONNECT : connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout == null ? PlatformTimeouts.OPA_READ : readTimeout;
  }

  public Duration getDecisionCacheTtl() {
    return decisionCacheTtl;
  }

  public void setDecisionCacheTtl(Duration decisionCacheTtl) {
    this.decisionCacheTtl = decisionCacheTtl == null ? Duration.ZERO : decisionCacheTtl;
  }

  public Duration getSqlFilterCacheTtl() {
    return sqlFilterCacheTtl;
  }

  public void setSqlFilterCacheTtl(Duration sqlFilterCacheTtl) {
    this.sqlFilterCacheTtl = sqlFilterCacheTtl == null ? Duration.ZERO : sqlFilterCacheTtl;
  }

  public int getMaxCacheEntries() {
    return maxCacheEntries;
  }

  public void setMaxCacheEntries(int maxCacheEntries) {
    this.maxCacheEntries = Math.max(64, maxCacheEntries);
  }

  public Duration getStepUpWindow() {
    return stepUpWindow;
  }

  public void setStepUpWindow(Duration stepUpWindow) {
    this.stepUpWindow = stepUpWindow == null ? Duration.ofMinutes(5) : stepUpWindow;
  }

  public Map<String, Duration> getTenantStepUpWindows() {
    return Map.copyOf(tenantStepUpWindows);
  }

  public void setTenantStepUpWindows(Map<String, Duration> tenantStepUpWindows) {
    this.tenantStepUpWindows =
        tenantStepUpWindows == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(tenantStepUpWindows);
  }

  public Duration stepUpWindowForTenant(UUID tenantId) {
    if (tenantId != null) {
      Duration override = tenantStepUpWindows.get(tenantId.toString());
      if (override != null && !override.isNegative() && !override.isZero()) {
        return override;
      }
    }
    return stepUpWindow;
  }
}
