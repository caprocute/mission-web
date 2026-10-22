package vn.viettel.okrkpi.iam.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Admin API Keycloak của cell, gọi trong mạng quản trị bằng client credentials riêng ({@code iam-user-sync}, vai trò
 * {@code realm-management} {@code view-users}, {@code manage-users}, {@code query-users}, {@code
 * manage-realm} để nhập người dùng giữ nguyên mã); bí mật lấy từ biến môi trường do chart cấp.
 */
@ConfigurationProperties("mission.user-sync.keycloak")
public record KeycloakProperties(
    String baseUrl,
    @DefaultValue("mission") String realm,
    @DefaultValue("iam-user-sync") String clientId,
    String clientSecret,
    @DefaultValue("3s") Duration timeout) {}
