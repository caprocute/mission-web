package vn.viettel.okrkpi.esign.infrastructure.sign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class HttpSourceDocumentClientTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void chuyenTiepTokenNguoiGoiSangModuleNguon() {
    Jwt jwt =
        Jwt.withTokenValue("tok-nguoi-ky")
            .header("alg", "none")
            .claim("sub", "u")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    assertThat(HttpSourceDocumentClient.callerHeaders().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer tok-nguoi-ky");
  }

  @Test
  void khongCoNguoiGoiThiKhongGanToken() {
    assertThat(HttpSourceDocumentClient.callerHeaders().containsKey(HttpHeaders.AUTHORIZATION))
        .isFalse();
  }
}
