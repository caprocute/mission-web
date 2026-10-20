package vn.viettel.okrkpi.shared.platformtest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** ADR-24: service ghi đè từng khoá của cấu hình mặc định, phần còn lại giữ nguyên TL-01. */
@SpringBootTest(
    classes = PlatformTestApplication.class,
    properties = {
      "resilience4j.circuitbreaker.instances.vneid.failureRateThreshold=30",
      "spring.http.client.read-timeout=60s",
      "management.server.port=9091"
    })
class PlatformDefaultsOverrideTest {

  @Autowired private CircuitBreakerRegistry circuitBreakers;
  @Autowired private Environment environment;

  @Test
  void serviceGhiDeTungKhoaConLaiGiuMacDinh() {
    CircuitBreakerConfig vneid = circuitBreakers.circuitBreaker("vneid").getCircuitBreakerConfig();
    assertThat(vneid.getFailureRateThreshold()).isEqualTo(30f);
    assertThat(vneid.getMinimumNumberOfCalls()).isEqualTo(20);
    assertThat(vneid.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(2));

    assertThat(environment.getProperty("spring.http.client.read-timeout")).isEqualTo("60s");
    assertThat(environment.getProperty("spring.http.client.connect-timeout")).isEqualTo("500ms");
    assertThat(environment.getProperty("management.server.port")).isEqualTo("9091");
  }
}
