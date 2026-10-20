package vn.viettel.okrkpi.platform.testkit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tập hợp các claim chuẩn trong JWT của hệ thống Mission theo TL-01 ("Mô hình xác thực và phân
 * quyền", ADR-05).
 *
 * @param issuer định danh máy chủ phát hành ({@code iss})
 * @param subject định danh người dùng ({@code sub})
 * @param tenantId định danh tổ chức thuê bao ({@code tenant_id})
 * @param orgPath đường dẫn cây đơn vị ({@code org_path})
 * @param roles danh sách mã vai trò theo ma trận phân quyền ({@code roles})
 * @param scope phạm vi OAuth 2.1 ({@code scope})
 * @param acr mức bảo đảm xác thực ({@code acr}, phục vụ xác thực tăng cường step-up)
 * @param authTime thời điểm xác thực tính bằng giây epoch ({@code auth_time})
 * @param issuedAt thời điểm phát hành token tính bằng giây epoch ({@code iat})
 * @param expiresAt thời điểm hết hạn token tính bằng giây epoch ({@code exp})
 * @param jti mã định danh duy nhất của token ({@code jti}, phục vụ thu hồi trên Valkey)
 */
public record TestJwtClaims(
    String issuer,
    String subject,
    UUID tenantId,
    String orgPath,
    List<String> roles,
    String scope,
    String acr,
    long authTime,
    long issuedAt,
    long expiresAt,
    String jti) {

  public static final String DEFAULT_ISSUER = "http://127.0.0.1:35080/realms/mission";
  public static final UUID DEFAULT_TENANT_ID =
      UUID.fromString("0192a000-0000-7000-8000-000000000001");
  public static final String DEFAULT_ORG_PATH = "/tw/vptw/vu-tong-hop";
  public static final String DEFAULT_SCOPE = "openid profile email mission:api";
  public static final String DEFAULT_ACR = "aal1";
  public static final String STEP_UP_ACR = "aal2";

  public TestJwtClaims {
    requireNonBlank(issuer, "issuer");
    requireNonBlank(subject, "subject");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    requireNonBlank(orgPath, "orgPath");
    Objects.requireNonNull(roles, "roles must not be null");
    roles = List.copyOf(roles);
    requireNonBlank(scope, "scope");
    requireNonBlank(acr, "acr");
    requireNonBlank(jti, "jti");
    if (expiresAt <= issuedAt) {
      throw new IllegalArgumentException("expiresAt must be greater than issuedAt");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  /** Bộ dựng {@link TestJwtClaims} với giá trị mặc định hợp lệ cho kiểm thử. */
  public static final class Builder {
    private String issuer = DEFAULT_ISSUER;
    private String subject = "0192a000-0000-7000-8000-000000000101";
    private UUID tenantId = DEFAULT_TENANT_ID;
    private String orgPath = DEFAULT_ORG_PATH;
    private List<String> roles = List.of("CAN_BO");
    private String scope = DEFAULT_SCOPE;
    private String acr = DEFAULT_ACR;
    private long authTime = Instant.now().getEpochSecond();
    private long issuedAt = Instant.now().getEpochSecond();
    private long expiresAt = Instant.now().plusSeconds(900).getEpochSecond();
    private String jti = UUID.randomUUID().toString();

    private Builder() {}

    public Builder issuer(String issuer) {
      this.issuer = issuer;
      return this;
    }

    public Builder subject(String subject) {
      this.subject = subject;
      return this;
    }

    public Builder tenantId(UUID tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder orgPath(String orgPath) {
      this.orgPath = orgPath;
      return this;
    }

    public Builder roles(List<String> roles) {
      this.roles = List.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
      return this;
    }

    public Builder roles(String... roles) {
      this.roles = List.of(roles);
      return this;
    }

    public Builder scope(String scope) {
      this.scope = scope;
      return this;
    }

    public Builder acr(String acr) {
      this.acr = acr;
      return this;
    }

    public Builder authTime(long authTime) {
      this.authTime = authTime;
      return this;
    }

    public Builder issuedAt(long issuedAt) {
      this.issuedAt = issuedAt;
      return this;
    }

    public Builder expiresAt(long expiresAt) {
      this.expiresAt = expiresAt;
      return this;
    }

    public Builder jti(String jti) {
      this.jti = jti;
      return this;
    }

    public TestJwtClaims build() {
      return new TestJwtClaims(
          issuer, subject, tenantId, orgPath, roles, scope, acr, authTime, issuedAt, expiresAt,
          jti);
    }
  }
}
