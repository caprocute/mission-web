package vn.viettel.okrkpi.org.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Ngữ cảnh Spring đầy đủ chạy bằng {@code svc_org} trên PostgreSQL 17 Testcontainers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "logging.structured.format.console=",
    "management.server.port=",
    "okrkpi.org.locked-periods[0].label=Quý I/2027",
    "okrkpi.org.locked-periods[0].start-date=2027-01-01",
    "okrkpi.org.locked-periods[0].end-date=2027-03-31"
})
@Import(IntegrationTestBase.Jwt.class)
public abstract class IntegrationTestBase {

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    PostgresSupport.start();
    registry.add("spring.datasource.url", PostgresSupport.PG::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "svc_org");
    registry.add("spring.datasource.password", () -> PostgresSupport.SVC_PASSWORD);
  }

  @TestConfiguration
  static class Jwt {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
    }
  }
}
