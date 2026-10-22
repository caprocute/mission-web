package vn.viettel.okrkpi.shared.infrastructure.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kết nối Admin API Keycloak của cell ({@code mission.config.keycloak-admin.*}); bí mật client lấy
 * từ biến môi trường do chart cấp, không ghi trong tệp cấu hình.
 */
@ConfigurationProperties("mission.config.keycloak-admin")
public record KeycloakAdminProperties(
    String baseUrl,
    @DefaultValue("mission") String realm,
    @DefaultValue("mission-core") String clientId,
    String clientSecret,
    @DefaultValue("tenant_admin") String adminRole) {}
