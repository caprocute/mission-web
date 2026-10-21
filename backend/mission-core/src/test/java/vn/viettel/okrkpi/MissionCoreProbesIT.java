package vn.viettel.okrkpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.viettel.okrkpi.platform.db.migration.guard.SchemaVersionGuard;
import vn.viettel.okrkpi.platform.db.migration.guard.SchemaVersionTooLowException;
import vn.viettel.okrkpi.shared.datasource.MissionCoreReadinessIndicator;
import vn.viettel.okrkpi.shared.datasource.MissionCoreSchemaVersionGuard;
import vn.viettel.okrkpi.shared.datasource.ModuleName;
import vn.viettel.okrkpi.shared.datasource.ReadWriteRoutingDataSource;

/**
 * Kiểm thử tích hợp {@code SVC-05 mission-core} với Testcontainers PostgreSQL 17: xác nhận 8 {@link
 * DataSource} nghiệp vụ + {@code org_replica}, {@link MissionCoreSchemaVersionGuard} cho 9 schema,
 * và đầu dò {@code liveness}/{@code readiness}/{@code prometheus} trên cổng quản trị (TL-01
 * L760–902, TL-04 L883–969).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureObservability
class MissionCoreProbesIT {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Value("${local.management.port}")
  private int managementPort;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired
  @Qualifier("moduleDataSources") private Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired private MissionCoreSchemaVersionGuard schemaVersionGuard;

  @Autowired private MissionCoreReadinessIndicator readinessIndicator;

  @Test
  void bootsWithAllNineSchemasVerifiedAndProbesUp() {
    assertThat(moduleDataSources).hasSize(8);
    schemaVersionGuard.verifyAllSchemas();

    ResponseEntity<String> liveness =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health/liveness", String.class);
    assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(liveness.getBody()).contains("\"status\":\"UP\"");

    ResponseEntity<String> readiness =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readiness.getBody())
        .contains("\"status\":\"UP\"")
        .contains("\"db.task\":\"UP\"")
        .contains("\"db.okr\":\"UP\"")
        .contains("\"db.org_replica\":\"UP\"");

    ResponseEntity<String> prometheus =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
    assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void readinessFailsWhenKafkaNotReadyWhileLivenessRemainsUp() {
    readinessIndicator.setKafkaProducerReady(false);
    try {
      ResponseEntity<String> readiness =
          restTemplate.getForEntity(
              "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);
      assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(readiness.getBody()).contains("\"status\":\"DOWN\"");

      ResponseEntity<String> liveness =
          restTemplate.getForEntity(
              "http://localhost:" + managementPort + "/actuator/health/liveness", String.class);
      assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(liveness.getBody()).contains("\"status\":\"UP\"");
    } finally {
      readinessIndicator.setKafkaProducerReady(true);
    }
  }

  @Test
  void schemaGuardRejectsWhenRequiredVersionHigherThanMigrated() {
    assertThatThrownBy(() -> SchemaVersionGuard.verify(taskDataSource, "task", "99.0"))
        .isInstanceOf(SchemaVersionTooLowException.class)
        .hasMessageContaining("task")
        .hasMessageContaining("99.0");
  }
}
