package vn.viettel.okrkpi.platform.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;

class ContainerSupportConfigTest {

  @Test
  void shouldResolveDefaultAndDynamicSshTunnelEnvironmentVariables() {
    MissionDevEnv defaults = new MissionDevEnv(Map.of());
    assertThat(defaults.host()).isEqualTo("127.0.0.1");
    assertThat(defaults.postgresPort()).isEqualTo(35432);
    assertThat(defaults.pgBouncerPort()).isEqualTo(35433);
    assertThat(defaults.valkeyPort()).isEqualTo(35379);
    assertThat(defaults.keycloakPort()).isEqualTo(35080);
    assertThat(defaults.opaPort()).isEqualTo(35181);
    assertThat(defaults.openBaoPort()).isEqualTo(35820);
    assertThat(defaults.kafkaExternalPort()).isEqualTo(35094);
    assertThat(defaults.apicurioPort()).isEqualTo(35081);
    assertThat(defaults.debeziumPort()).isEqualTo(35083);
    assertThat(defaults.directJdbcUrl("mission"))
        .isEqualTo("jdbc:postgresql://127.0.0.1:35432/mission");
    assertThat(defaults.pgBouncerJdbcUrl("mission"))
        .isEqualTo("jdbc:postgresql://127.0.0.1:35433/mission");
    assertThat(defaults.opaBaseUrl()).isEqualTo("http://127.0.0.1:35181");
    assertThat(defaults.openBaoBaseUrl()).isEqualTo("http://127.0.0.1:35820");
    assertThat(defaults.keycloakIssuerUri()).isEqualTo("http://127.0.0.1:35080/realms/mission");

    MissionDevEnv customTunnel =
        new MissionDevEnv(
            Map.of(
                "DEV_HOST", "127.0.0.1",
                "POSTGRES_PORT", "45432",
                "PGBOUNCER_PORT", "45433",
                "VALKEY_PORT", "45379",
                "KEYCLOAK_PORT", "45080",
                "OPA_PORT", "45181",
                "OPENBAO_PORT", "45820",
                "KAFKA_EXTERNAL_PORT", "45094",
                "APICURIO_PORT", "45081",
                "DEBEZIUM_PORT", "45083"));
    assertThat(customTunnel.directJdbcUrl("mission"))
        .isEqualTo("jdbc:postgresql://127.0.0.1:45432/mission");
    assertThat(customTunnel.pgBouncerJdbcUrl("mission"))
        .isEqualTo("jdbc:postgresql://127.0.0.1:45433/mission");
    assertThat(customTunnel.opaBaseUrl()).isEqualTo("http://127.0.0.1:45181");
    assertThat(customTunnel.openBaoBaseUrl()).isEqualTo("http://127.0.0.1:45820");
    assertThat(customTunnel.keycloakIssuerUri()).isEqualTo("http://127.0.0.1:45080/realms/mission");

    MissionDevEnv invalidPortEnv = new MissionDevEnv(Map.of("OPA_PORT", "70000"));
    assertThatThrownBy(invalidPortEnv::opaPort).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldExposeAll21MissionSchemasAndFourRoleConventions() {
    assertThat(PostgresContainerSupport.allMissionSchemas())
        .hasSize(21)
        .contains(
            "config",
            "policy",
            "okr",
            "task",
            "product",
            "appraisal",
            "dossier",
            "oversight",
            "org_replica",
            "modulith",
            "keyring",
            "org",
            "scoring",
            "evidence",
            "docgen",
            "notify",
            "integ",
            "registry",
            "esign",
            "ai",
            "apicurio");

    assertThat(PostgresContainerSupport.migUsername("scoring")).isEqualTo("mig_scoring");
    assertThat(PostgresContainerSupport.svcUsername("scoring")).isEqualTo("svc_scoring");
    assertThat(PostgresContainerSupport.roUsername("scoring")).isEqualTo("ro_scoring");
    assertThat(PostgresContainerSupport.cdcUsername("scoring")).isEqualTo("cdc_scoring");

    Map<String, String> props =
        PostgresContainerSupport.buildSpringProperties(
            "jdbc:postgresql://127.0.0.1:35433/mission", "scoring", "secret");
    assertThat(props)
        .containsEntry("spring.datasource.username", "svc_scoring")
        .containsEntry("spring.flyway.user", "mig_scoring")
        .containsEntry("spring.flyway.schemas", "scoring");
  }

  @Test
  void shouldVerifyKafkaCatalogTopicsAndRetryDlqNaming() {
    assertThat(KafkaContainerSupport.BUSINESS_TOPICS).hasSize(26);
    assertThat(KafkaContainerSupport.TECHNICAL_TOPICS)
        .contains(
            "sys.ledger.append.v1",
            "sys.audit.v1",
            "sys.evidence.object.stored.v1",
            "sys.tenant.provisioned.v1",
            "iam.auth.event.v1",
            "registry.tenant.changed.v1",
            "org.bootstrap.v1",
            "period.drain.marker");

    assertThat(KafkaContainerSupport.isCompactDeleteTopic("org.unit.changed.v1")).isTrue();
    assertThat(KafkaContainerSupport.isCompactDeleteTopic("org.user-assignment.changed.v1"))
        .isTrue();
    assertThat(KafkaContainerSupport.isCompactDeleteTopic("org.bootstrap.v1")).isTrue();
    assertThat(KafkaContainerSupport.isCompactDeleteTopic("task.assigned.v1")).isFalse();

    assertThat(KafkaContainerSupport.retryTopic("task.accepted.v1", "scoring-service"))
        .isEqualTo("task.accepted.v1.retry.scoring-service");
    assertThat(KafkaContainerSupport.dlqTopic("task.accepted.v1", "scoring-service"))
        .isEqualTo("task.accepted.v1.dlq.scoring-service");

    assertThatThrownBy(() -> KafkaContainerSupport.retryTopic(" ", "scoring-service"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldBuildSpringPropertiesAndResolveDevFilesForAllSupports() {
    assertThat(MissionTestImages.POSTGRES).isEqualTo("postgres:17");
    assertThat(MissionTestImages.PGBOUNCER).startsWith("edoburu/pgbouncer:");
    assertThat(MissionTestImages.VALKEY).isEqualTo("valkey/valkey:8-alpine");
    assertThat(MissionTestImages.KAFKA).isEqualTo("apache/kafka:3.9.1");
    assertThat(MissionTestImages.APICURIO).startsWith("quay.io/apicurio/apicurio-registry:3.");
    assertThat(MissionTestImages.DEBEZIUM).startsWith("quay.io/debezium/connect:3.");
    assertThat(MissionTestImages.KEYCLOAK).startsWith("quay.io/keycloak/keycloak:26.");
    assertThat(MissionTestImages.OPA).startsWith("openpolicyagent/opa:1.");
    assertThat(MissionTestImages.OPENBAO).startsWith("quay.io/openbao/openbao:2.");

    assertThat(ValkeyContainerSupport.buildSpringProperties("127.0.0.1", 35379, "pw"))
        .containsEntry("spring.data.redis.host", "127.0.0.1")
        .containsEntry("spring.data.redis.port", "35379");

    assertThat(KeycloakContainerSupport.buildSpringProperties("127.0.0.1", 35080))
        .containsEntry(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "http://127.0.0.1:35080/realms/mission");
    assertThat(KeycloakContainerSupport.resolveRealmFile()).isNotNull();

    assertThat(
            SecurityContainerSupport.buildSpringProperties(
                "127.0.0.1", 35181, "127.0.0.1", 35820, "tok"))
        .containsEntry(
            "okrkpi.security.opa.url", "http://127.0.0.1:35181/v1/data/okrkpi/authz/decision")
        .containsEntry("okrkpi.crypto.openbao.url", "http://127.0.0.1:35820");
    assertThat(SecurityContainerSupport.resolveOpaPolicyFile()).isNotNull();

    assertThat(
            KafkaContainerSupport.buildSpringProperties(
                "127.0.0.1:35094", "http://127.0.0.1:35081"))
        .containsEntry("spring.kafka.bootstrap-servers", "127.0.0.1:35094")
        .containsEntry(
            "okrkpi.events.apicurio.registry-url", "http://127.0.0.1:35081/apis/registry/v3");

    Map<String, Object> registered = new LinkedHashMap<>();
    DynamicPropertyRegistry registry = (name, valueSupplier) -> registered.put(name, valueSupplier);
    PostgresContainerSupport.registerProperties(registry, "task");
    ValkeyContainerSupport.registerProperties(registry);
    KeycloakContainerSupport.registerProperties(registry);
    SecurityContainerSupport.registerProperties(registry);
    KafkaContainerSupport.registerProperties(registry);

    assertThat(registered)
        .containsKeys(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.flyway.user",
            "spring.data.redis.host",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "okrkpi.security.opa.url",
            "okrkpi.crypto.openbao.url",
            "spring.kafka.bootstrap-servers",
            "okrkpi.events.apicurio.registry-url");
  }
}
