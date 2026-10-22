package vn.viettel.okrkpi.esign;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kiểm thử tích hợp với Testcontainers PostgreSQL 17 khởi động ứng dụng và gọi probe trên cổng quản
 * trị (NEN-019 mục 3). Tự động bỏ qua khi chạy trong môi trường không có Docker daemon.
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ServiceTemplateProbesIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
  }

  @Value("${local.management.port}")
  private int managementPort;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void probesUpAgainstPostgresContainer() {
    ResponseEntity<String> liveness =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health/liveness", String.class);
    assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(liveness.getBody()).contains("\"status\":\"UP\"");

    ResponseEntity<String> readiness =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readiness.getBody()).contains("\"status\":\"UP\"");

    ResponseEntity<String> prometheus =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
    assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
