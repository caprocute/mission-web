package vn.viettel.okrkpi.iam.vneid;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Tham số triển khai của môi giới VNeID đọc từ biến môi trường của pod Keycloak (DV-02-04). Bí mật (khoá liên kết,
 * client secret tài khoản dịch vụ) do External Secrets đồng bộ từ OpenBao vào Secret của Keycloak; địa chỉ, client và
 * tên claim của VNeID nằm trong cấu hình nhà cung cấp danh tính của mẫu realm, không ở đây.
 *
 * <ul>
 *   <li>{@code MISSION_VNEID_LINK_KEY}: khoá HMAC băm mã chủ thể VNeID, base64, tối thiểu 32 byte.
 *   <li>{@code MISSION_ORG_SERVICE_URL}: địa chỉ nội bộ của org-service.
 *   <li>{@code MISSION_IAM_BROKER_TOKEN_URL}, {@code MISSION_IAM_BROKER_CLIENT_ID}, {@code
 *       MISSION_IAM_BROKER_CLIENT_SECRET}: tài khoản dịch vụ gọi org-service.
 *   <li>{@code MISSION_IAM_BROKER_TIMEOUT_MS}: thời hạn gọi org-service (mặc định 3000).
 * </ul>
 */
record VneidSettings(byte[] linkKey, String orgServiceUrl, String brokerTokenUrl, String brokerClientId,
    String brokerClientSecret, Duration orgTimeout) {

  static VneidSettings fromEnv() {
    return from(System::getenv);
  }

  static VneidSettings from(UnaryOperator<String> env) {
    String key = env.apply("MISSION_VNEID_LINK_KEY");
    byte[] linkKey = null;
    if (key != null && !key.isBlank()) {
      try {
        linkKey = Base64.getDecoder().decode(key.trim());
      } catch (IllegalArgumentException e) {
        linkKey = key.trim().getBytes(StandardCharsets.UTF_8);
      }
    }
    String org = env.apply("MISSION_ORG_SERVICE_URL");
    String timeout = env.apply("MISSION_IAM_BROKER_TIMEOUT_MS");
    return new VneidSettings(linkKey,
        org == null ? "" : org.replaceAll("/+$", ""),
        orElse(env.apply("MISSION_IAM_BROKER_TOKEN_URL"), ""),
        orElse(env.apply("MISSION_IAM_BROKER_CLIENT_ID"), "mission-iam-broker"),
        orElse(env.apply("MISSION_IAM_BROKER_CLIENT_SECRET"), ""),
        Duration.ofMillis(timeout == null || timeout.isBlank() ? 3000 : Long.parseLong(timeout.trim())));
  }

  static VneidSettings from(Map<String, String> env) {
    return from(env::get);
  }

  private static String orElse(String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v.trim();
  }

  @Override
  public String toString() {
    return "VneidSettings[orgServiceUrl=" + orgServiceUrl + ", brokerClientId=" + brokerClientId + "]";
  }
}
