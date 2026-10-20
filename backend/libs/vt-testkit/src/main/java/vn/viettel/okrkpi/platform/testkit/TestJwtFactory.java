package vn.viettel.okrkpi.platform.testkit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bộ sinh và xác thực JWT ký số RSA-2048 ({@code RS256}) kèm JWKS nội bộ dùng trong kiểm thử đơn vị
 * và kiểm thử tích hợp mà không cần khởi động container Keycloak (NEN-020, ADR-05, TL-01).
 */
public final class TestJwtFactory {

  public static final String DEFAULT_KEY_ID = "mission-test-rs256-kid-1";

  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TestJwtFactory DEFAULT_INSTANCE = new TestJwtFactory(DEFAULT_KEY_ID);

  private final String keyId;
  private final KeyPair keyPair;

  public TestJwtFactory() {
    this(DEFAULT_KEY_ID, generateRsaKeyPair());
  }

  public TestJwtFactory(String keyId) {
    this(keyId, generateRsaKeyPair());
  }

  public TestJwtFactory(String keyId, KeyPair keyPair) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException("keyId must not be blank");
    }
    this.keyId = keyId;
    this.keyPair = Objects.requireNonNull(keyPair, "keyPair must not be null");
  }

  /** Trả về instance dùng chung trong JVM để giảm chi phí sinh cặp khoá RSA-2048. */
  public static TestJwtFactory defaultInstance() {
    return DEFAULT_INSTANCE;
  }

  /** Tạo {@link TestJwtClaims} mặc định cho một hoặc nhiều vai trò trong ma trận phân quyền AC. */
  public static TestJwtClaims claimsForRoles(String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("roles must not be empty");
    }
    return TestJwtClaims.builder().roles(roles).build();
  }

  /**
   * Tạo {@link TestJwtClaims} cho xác thực tăng cường (step-up authentication, {@code acr=aal2}).
   */
  public static TestJwtClaims stepUpClaimsForRoles(String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("roles must not be empty");
    }
    long now = Instant.now().getEpochSecond();
    return TestJwtClaims.builder()
        .roles(roles)
        .acr(TestJwtClaims.STEP_UP_ACR)
        .authTime(now)
        .issuedAt(now)
        .expiresAt(now + 300)
        .build();
  }

  public String keyId() {
    return keyId;
  }

  public PublicKey publicKey() {
    return keyPair.getPublic();
  }

  public PrivateKey privateKey() {
    return keyPair.getPrivate();
  }

  /** Phát hành chuỗi JWT chuẩn {@code header.payload.signature} ký bằng {@code RS256}. */
  public String issueToken(TestJwtClaims claims) {
    Objects.requireNonNull(claims, "claims must not be null");
    try {
      Map<String, Object> header = new LinkedHashMap<>();
      header.put("alg", "RS256");
      header.put("typ", "JWT");
      header.put("kid", keyId);

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("iss", claims.issuer());
      payload.put("sub", claims.subject());
      payload.put("tenant_id", claims.tenantId().toString());
      payload.put("org_path", claims.orgPath());
      payload.put("roles", claims.roles());
      payload.put("realm_access", Map.of("roles", claims.roles()));
      payload.put("scope", claims.scope());
      payload.put("acr", claims.acr());
      payload.put("auth_time", claims.authTime());
      payload.put("iat", claims.issuedAt());
      payload.put("exp", claims.expiresAt());
      payload.put("jti", claims.jti());

      String encodedHeader =
          BASE64_URL_ENCODER.encodeToString(OBJECT_MAPPER.writeValueAsBytes(header));
      String encodedPayload =
          BASE64_URL_ENCODER.encodeToString(OBJECT_MAPPER.writeValueAsBytes(payload));
      String signingInput = encodedHeader + "." + encodedPayload;

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(keyPair.getPrivate());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      String encodedSignature = BASE64_URL_ENCODER.encodeToString(signer.sign());

      return signingInput + "." + encodedSignature;
    } catch (Exception ex) {
      throw new IllegalStateException("Không thể ký JWT thử nghiệm", ex);
    }
  }

  /**
   * Phát hành chuỗi header {@code Bearer <jwt>} tiện dùng cho {@code MockMvc} / {@code
   * WebTestClient}.
   */
  public String bearerHeader(TestJwtClaims claims) {
    return "Bearer " + issueToken(claims);
  }

  /**
   * Xác thực chữ ký {@code RS256}, kiểm tra hạn dùng và giải mã ngược chuỗi JWT thành {@link
   * TestJwtClaims}.
   */
  public TestJwtClaims verifyAndDecode(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Invalid JWT format: expected 3 parts");
    }
    try {
      String signingInput = parts[0] + "." + parts[1];
      byte[] signatureBytes = BASE64_URL_DECODER.decode(parts[2]);

      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(keyPair.getPublic());
      verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(signatureBytes)) {
        throw new IllegalArgumentException("JWT signature verification failed");
      }

      Map<String, Object> payload =
          OBJECT_MAPPER.readValue(
              BASE64_URL_DECODER.decode(parts[1]), new TypeReference<Map<String, Object>>() {});

      long exp = ((Number) payload.get("exp")).longValue();
      if (exp <= Instant.now().getEpochSecond()) {
        throw new IllegalArgumentException("JWT token has expired");
      }

      @SuppressWarnings("unchecked")
      List<String> roles = (List<String>) payload.get("roles");

      return new TestJwtClaims(
          (String) payload.get("iss"),
          (String) payload.get("sub"),
          UUID.fromString((String) payload.get("tenant_id")),
          (String) payload.get("org_path"),
          roles,
          (String) payload.get("scope"),
          (String) payload.get("acr"),
          ((Number) payload.get("auth_time")).longValue(),
          ((Number) payload.get("iat")).longValue(),
          exp,
          (String) payload.get("jti"));
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Failed to decode or verify JWT", ex);
    }
  }

  /**
   * Sinh chuỗi JSON JWKS (RFC 7517) chứa khoá công khai RSA hiện tại để cung cấp cho Resource
   * Server.
   */
  public String jwksJson() {
    RSAPublicKey rsaPub = (RSAPublicKey) keyPair.getPublic();
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("kty", "RSA");
    jwk.put("use", "sig");
    jwk.put("alg", "RS256");
    jwk.put("kid", keyId);
    jwk.put("n", encodeUnsignedBigInt(rsaPub.getModulus()));
    jwk.put("e", encodeUnsignedBigInt(rsaPub.getPublicExponent()));
    try {
      return OBJECT_MAPPER.writeValueAsString(Map.of("keys", List.of(jwk)));
    } catch (Exception ex) {
      throw new IllegalStateException("Không thể tạo JSON JWKS", ex);
    }
  }

  private static String encodeUnsignedBigInt(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return BASE64_URL_ENCODER.encodeToString(bytes);
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Không thể sinh cặp khoá RSA-2048", ex);
    }
  }
}
