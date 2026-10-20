package vn.viettel.okrkpi.shared.crypto;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.viettel.okrkpi.shared.resilience.PlatformTimeouts;

/**
 * Cấu hình mã hoá trường phong bì C2, bộ đệm DEK và kết nối OpenBao Transit (NEN-015, ADR-06,
 * ADR-17, TL-01 "Mã hoá phong bì cho dữ liệu C2", TL-04 "Quản lý bí mật và khoá: OpenBao, ESO,
 * HSM").
 *
 * <p>Cấu hình này <b>không</b> có thuộc tính nhận khoá mật mã hay token dạng rõ: xác thực OpenBao
 * chỉ đi qua tệp token do CSI/ESO gắn ({@link #tokenFile}) hoặc Kubernetes ServiceAccount JWT
 * ({@link #kubernetesRole} + {@link #serviceAccountTokenFile}).
 */
@ConfigurationProperties(prefix = "okrkpi.crypto")
public class CryptoProperties {

  private boolean enabled = true;
  private URI openBaoBaseUrl = URI.create("http://127.0.0.1:8200");
  private String transitMount = "transit";
  private String kekPrefix = "kek-";
  private Duration connectTimeout = PlatformTimeouts.OPENBAO_CONNECT;
  private Duration readTimeout = PlatformTimeouts.OPENBAO_READ;
  private Duration dekCacheTtl = DekCache.DEFAULT_TTL;
  private int dekCacheMaxSize = DekCache.DEFAULT_MAX_SIZE;
  private boolean autoCreateKek = false;

  /** Đường dẫn tệp chứa token OpenBao do CSI Secret Store hoặc ESO gắn vào pod. */
  private Path tokenFile;

  /** Mount point của phương thức xác thực Kubernetes trên OpenBao (mặc định {@code kubernetes}). */
  private String kubernetesMount = "kubernetes";

  /** Tên role Kubernetes auth trên OpenBao (nếu xác thực bằng ServiceAccount JWT). */
  private String kubernetesRole;

  /** Đường dẫn tệp ServiceAccount JWT gắn trong pod Kubernetes. */
  private Path serviceAccountTokenFile;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public URI getOpenBaoBaseUrl() {
    return openBaoBaseUrl;
  }

  public void setOpenBaoBaseUrl(URI openBaoBaseUrl) {
    this.openBaoBaseUrl =
        openBaoBaseUrl == null ? URI.create("http://127.0.0.1:8200") : openBaoBaseUrl;
  }

  public String getTransitMount() {
    return transitMount;
  }

  public void setTransitMount(String transitMount) {
    this.transitMount =
        transitMount == null || transitMount.isBlank() ? "transit" : transitMount.trim();
  }

  public String getKekPrefix() {
    return kekPrefix;
  }

  public void setKekPrefix(String kekPrefix) {
    this.kekPrefix = kekPrefix == null || kekPrefix.isBlank() ? "kek-" : kekPrefix.trim();
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout =
        connectTimeout == null ? PlatformTimeouts.OPENBAO_CONNECT : connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout == null ? PlatformTimeouts.OPENBAO_READ : readTimeout;
  }

  public Duration getDekCacheTtl() {
    return dekCacheTtl;
  }

  public void setDekCacheTtl(Duration dekCacheTtl) {
    this.dekCacheTtl = dekCacheTtl == null ? DekCache.DEFAULT_TTL : dekCacheTtl;
  }

  public int getDekCacheMaxSize() {
    return dekCacheMaxSize;
  }

  public void setDekCacheMaxSize(int dekCacheMaxSize) {
    this.dekCacheMaxSize = dekCacheMaxSize <= 0 ? DekCache.DEFAULT_MAX_SIZE : dekCacheMaxSize;
  }

  public boolean isAutoCreateKek() {
    return autoCreateKek;
  }

  public void setAutoCreateKek(boolean autoCreateKek) {
    this.autoCreateKek = autoCreateKek;
  }

  public Path getTokenFile() {
    return tokenFile;
  }

  public void setTokenFile(Path tokenFile) {
    this.tokenFile = tokenFile;
  }

  public String getKubernetesMount() {
    return kubernetesMount;
  }

  public void setKubernetesMount(String kubernetesMount) {
    this.kubernetesMount =
        kubernetesMount == null || kubernetesMount.isBlank()
            ? "kubernetes"
            : kubernetesMount.trim();
  }

  public String getKubernetesRole() {
    return kubernetesRole;
  }

  public void setKubernetesRole(String kubernetesRole) {
    this.kubernetesRole =
        kubernetesRole == null || kubernetesRole.isBlank() ? null : kubernetesRole.trim();
  }

  public Path getServiceAccountTokenFile() {
    return serviceAccountTokenFile;
  }

  public void setServiceAccountTokenFile(Path serviceAccountTokenFile) {
    this.serviceAccountTokenFile = serviceAccountTokenFile;
  }
}
