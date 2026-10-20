package vn.viettel.okrkpi.platform.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestJwtFactoryTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SYS_ADMIN",
        "LANH_DAO_DON_VI",
        "THU_TRUONG_CO_QUAN",
        "CHUYEN_VIEN_THAM_MUU",
        "CAN_BO",
        "HOI_DONG_THAM_DINH",
        "KIEM_TRA_GIAM_SAT"
      })
  void shouldIssueAndVerifyRs256TokenForEveryMatrixRole(String role) {
    TestJwtFactory factory = TestJwtFactory.defaultInstance();
    TestJwtClaims claims = TestJwtFactory.claimsForRoles(role);

    String token = factory.issueToken(claims);
    assertThat(token).isNotBlank();
    assertThat(factory.bearerHeader(claims)).startsWith("Bearer " + token.substring(0, 16));

    TestJwtClaims decoded = factory.verifyAndDecode(token);
    assertThat(decoded.issuer()).isEqualTo(TestJwtClaims.DEFAULT_ISSUER);
    assertThat(decoded.tenantId()).isEqualTo(TestJwtClaims.DEFAULT_TENANT_ID);
    assertThat(decoded.orgPath()).isEqualTo(TestJwtClaims.DEFAULT_ORG_PATH);
    assertThat(decoded.roles()).containsExactly(role);
    assertThat(decoded.scope()).isEqualTo(TestJwtClaims.DEFAULT_SCOPE);
    assertThat(decoded.acr()).isEqualTo(TestJwtClaims.DEFAULT_ACR);
    assertThat(decoded.jti()).isEqualTo(claims.jti());
  }

  @Test
  void shouldIssueStepUpTokenWithAal2AndCustomClaims() {
    TestJwtFactory factory = new TestJwtFactory("custom-kid-2026");
    UUID tenantId = UUID.fromString("0192a000-0000-7000-8000-000000000999");
    long now = Instant.now().getEpochSecond();

    TestJwtClaims customClaims =
        TestJwtClaims.builder()
            .issuer("https://id.mission.local/realms/mission")
            .subject("user-999")
            .tenantId(tenantId)
            .orgPath("/tw/bct/vptw/vu-1")
            .roles(List.of("LANH_DAO_DON_VI", "HOI_DONG_THAM_DINH"))
            .scope("openid profile mission:api mission:sign")
            .acr(TestJwtClaims.STEP_UP_ACR)
            .authTime(now)
            .issuedAt(now)
            .expiresAt(now + 600)
            .jti("jti-step-up-001")
            .build();

    String token = factory.issueToken(customClaims);
    TestJwtClaims verified = factory.verifyAndDecode(token);

    assertThat(verified).isEqualTo(customClaims);
    assertThat(factory.keyId()).isEqualTo("custom-kid-2026");
    assertThat(factory.publicKey()).isNotNull();
    assertThat(factory.privateKey()).isNotNull();

    String jwks = factory.jwksJson();
    assertThat(jwks)
        .contains("\"kty\":\"RSA\"")
        .contains("\"alg\":\"RS256\"")
        .contains("\"kid\":\"custom-kid-2026\"");

    TestJwtClaims stepUpHelper = TestJwtFactory.stepUpClaimsForRoles("THU_TRUONG_CO_QUAN");
    assertThat(stepUpHelper.acr()).isEqualTo(TestJwtClaims.STEP_UP_ACR);
    assertThat(stepUpHelper.roles()).containsExactly("THU_TRUONG_CO_QUAN");
  }

  @Test
  void shouldRejectTamperedOrWrongKeyOrMalformedTokens() {
    TestJwtFactory factory1 = new TestJwtFactory("kid-1");
    TestJwtFactory factory2 = new TestJwtFactory("kid-2");

    String token = factory1.issueToken(TestJwtFactory.claimsForRoles("CAN_BO"));

    assertThatThrownBy(() -> factory2.verifyAndDecode(token))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature verification failed");

    String tampered = token.substring(0, token.length() - 4) + "AAAA";
    assertThatThrownBy(() -> factory1.verifyAndDecode(tampered))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> factory1.verifyAndDecode("invalid.token"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected 3 parts");

    assertThatThrownBy(() -> factory1.verifyAndDecode(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token must not be blank");
  }

  @Test
  void shouldRejectExpiredClaimsAndBlankFields() {
    long past = Instant.now().minusSeconds(600).getEpochSecond();
    TestJwtClaims expiredClaims =
        TestJwtClaims.builder().issuedAt(past - 300).expiresAt(past).build();
    String expiredToken = TestJwtFactory.defaultInstance().issueToken(expiredClaims);

    assertThatThrownBy(() -> TestJwtFactory.defaultInstance().verifyAndDecode(expiredToken))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expired");

    assertThatThrownBy(() -> TestJwtClaims.builder().issuedAt(1000L).expiresAt(1000L).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiresAt must be greater than issuedAt");

    assertThatThrownBy(() -> TestJwtClaims.builder().subject(" ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subject must not be blank");

    assertThatThrownBy(() -> TestJwtFactory.claimsForRoles())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roles must not be empty");
  }
}
