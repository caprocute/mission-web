package vn.viettel.okrkpi.platform.testkit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Hỗ trợ khởi tạo Kafka 3.9 chế độ KRaft, Apicurio Registry 3.x và Debezium Connect 3.x dùng chung
 * ({@code withReuse(true)}) cùng danh mục 26 topic nghiệp vụ ({@code EVT-01..EVT-26}), topic kỹ
 * thuật và quy ước tên topic {@code .retry.<consumer>} / {@code .dlq.<consumer>} (NEN-020, ADR-03).
 */
public final class KafkaContainerSupport {

  /** 26 topic sự kiện nghiệp vụ chuẩn theo 00-co-so-thiet-ke-chung.md mục 6 (EVT-01..EVT-26). */
  public static final List<String> BUSINESS_TOPICS =
      List.of(
          "org.unit.changed.v1",
          "org.user-assignment.changed.v1",
          "okr.objective.published.v1",
          "okr.kr.progress-updated.v1",
          "okr.alignment.changed.v1",
          "okr.alert.triggered.v1",
          "task.assigned.v1",
          "task.status-changed.v1",
          "task.progress-updated.v1",
          "task.accepted.v1",
          "task.overdue.v1",
          "product.submitted.v1",
          "product.accepted.v1",
          "product.rejected.v1",
          "evidence.uploaded.v1",
          "evidence.verified.v1",
          "scoring.kpi-recalculated.v1",
          "scoring.snapshot.finalized.v1",
          "scoring.period.locked.v1",
          "appraisal.round.opened.v1",
          "appraisal.submitted.v1",
          "appraisal.council-approved.v1",
          "appraisal.result.notified.v1",
          "appraisal.appeal.filed.v1",
          "dossier.generated.v1",
          "dossier.signed.v1");

  /** Các topic kỹ thuật hệ thống theo TL-01 chương 4 & 5 và NEN-020 mục 3. */
  public static final List<String> TECHNICAL_TOPICS =
      List.of(
          "sys.ledger.append.v1",
          "sys.audit.v1",
          "sys.evidence.object.stored.v1",
          "sys.tenant.provisioned.v1",
          "iam.auth.event.v1",
          "registry.tenant.changed.v1",
          "org.bootstrap.v1",
          "period.drain.marker");

  /** Các topic đối chiếu danh mục dùng chính sách dọn dẹp {@code compact,delete}. */
  public static final Set<String> COMPACT_DELETE_TOPICS =
      Set.of("org.unit.changed.v1", "org.user-assignment.changed.v1", "org.bootstrap.v1");

  private static volatile KafkaContainer sharedKafka;
  private static volatile GenericContainer<?> sharedApicurio;

  private KafkaContainerSupport() {}

  /** Tạo mới cấu hình Kafka 3.9 KRaft container (chưa khởi động). */
  @SuppressWarnings("resource")
  public static KafkaContainer createKafkaContainer() {
    return new KafkaContainer(DockerImageName.parse(MissionTestImages.KAFKA)).withReuse(true);
  }

  /** Tạo mới cấu hình Apicurio Registry 3.x container (chế độ bộ nhớ cho kiểm thử nhanh). */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createApicurioContainer() {
    return new GenericContainer<>(DockerImageName.parse(MissionTestImages.APICURIO))
        .withExposedPorts(8080)
        .withEnv("QUARKUS_HTTP_CORS_ENABLED", "true")
        .withReuse(true);
  }

  /** Tạo mới cấu hình Debezium Connect 3.x container gắn vào mạng kiểm thử. */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createDebeziumContainer(
      Network network, String kafkaBootstrapInsideNetwork) {
    Objects.requireNonNull(network, "network must not be null");
    Objects.requireNonNull(
        kafkaBootstrapInsideNetwork, "kafkaBootstrapInsideNetwork must not be null");
    return new GenericContainer<>(DockerImageName.parse(MissionTestImages.DEBEZIUM))
        .withNetwork(network)
        .withExposedPorts(8083)
        .withEnv("BOOTSTRAP_SERVERS", kafkaBootstrapInsideNetwork)
        .withEnv("GROUP_ID", "1")
        .withEnv("CONFIG_STORAGE_TOPIC", "_debezium_configs")
        .withEnv("OFFSET_STORAGE_TOPIC", "_debezium_offsets")
        .withEnv("STATUS_STORAGE_TOPIC", "_debezium_status")
        .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
        .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
        .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
        .withReuse(true);
  }

  public static synchronized KafkaContainer sharedKafka() {
    if (sharedKafka == null) {
      sharedKafka = createKafkaContainer();
    }
    if (!sharedKafka.isRunning()) {
      sharedKafka.start();
    }
    return sharedKafka;
  }

  public static synchronized GenericContainer<?> sharedApicurio() {
    if (sharedApicurio == null) {
      sharedApicurio = createApicurioContainer();
    }
    if (!sharedApicurio.isRunning()) {
      sharedApicurio.start();
    }
    return sharedApicurio;
  }

  /** Sinh tên topic thử lại chuẩn {@code <topic>.retry.<consumer>}. */
  public static String retryTopic(String baseTopic, String consumerName) {
    validateNonBlank(baseTopic, "baseTopic");
    validateNonBlank(consumerName, "consumerName");
    return baseTopic.trim() + ".retry." + consumerName.trim();
  }

  /** Sinh tên topic hàng đợi lỗi chuẩn {@code <topic>.dlq.<consumer>}. */
  public static String dlqTopic(String baseTopic, String consumerName) {
    validateNonBlank(baseTopic, "baseTopic");
    validateNonBlank(consumerName, "consumerName");
    return baseTopic.trim() + ".dlq." + consumerName.trim();
  }

  /** Kiểm tra một topic có bắt buộc bật {@code cleanup.policy=compact,delete} hay không. */
  public static boolean isCompactDeleteTopic(String topic) {
    return topic != null && (COMPACT_DELETE_TOPICS.contains(topic) || topic.startsWith("org."));
  }

  public static Map<String, String> buildSpringProperties(
      String bootstrapServers, String apicurioBaseUrl) {
    validateNonBlank(bootstrapServers, "bootstrapServers");
    validateNonBlank(apicurioBaseUrl, "apicurioBaseUrl");
    return Map.of(
        "spring.kafka.bootstrap-servers",
        bootstrapServers,
        "okrkpi.events.apicurio.registry-url",
        apicurioBaseUrl + "/apis/registry/v3");
  }

  public static void registerProperties(DynamicPropertyRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    registry.add("spring.kafka.bootstrap-servers", () -> sharedKafka().getBootstrapServers());
    registry.add(
        "okrkpi.events.apicurio.registry-url",
        () ->
            "http://"
                + sharedApicurio().getHost()
                + ":"
                + sharedApicurio().getMappedPort(8080)
                + "/apis/registry/v3");
  }

  private static void validateNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
