package vn.viettel.okrkpi.esign.infrastructure.seal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ký tổ chức {@code okrkpi.esign.org-seal} (DV-13-10). {@code provider} LOCAL dùng cặp khoá EC
 * trong tệp do OpenBao Agent đặt (chỉ đường dẫn, không ghi khoá vào cấu hình); MYSIGN dùng
 * credential tổ chức ở MySign. {@code purposes} là các mục đích được ký, {@code callerRoles} là vai
 * trò realm của dịch vụ được gọi.
 */
@ConfigurationProperties("okrkpi.esign.org-seal")
public record OrganizationSealProperties(
    boolean enabled,
    String provider,
    String privateKeyPath,
    String publicKeyPath,
    String credentialId,
    List<String> purposes,
    List<String> callerRoles) {

  public OrganizationSealProperties {
    provider = provider == null || provider.isBlank() ? "LOCAL" : provider;
    purposes =
        purposes == null || purposes.isEmpty()
            ? List.of("GOI_CHUYEN_LIEN_CELL")
            : List.copyOf(purposes);
    callerRoles =
        callerRoles == null || callerRoles.isEmpty()
            ? List.of("svc_integration_gateway")
            : List.copyOf(callerRoles);
  }
}
