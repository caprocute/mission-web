package vn.viettel.okrkpi.iam.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.iam.v1.AuthEvent;

/** DV-02-05: sự kiện thu hồi trên {@code iam.auth.event.v1} được ghi thành khoá danh sách thu hồi trong Valkey. */
@Testcontainers
@SpringBootTest
class RevocationListenerIT {

  private static final String TENANT = "0192f000-0000-7000-8000-0000000000a1";

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer(
      DockerImageName.parse("mirror.gcr.io/apache/kafka:3.9.1").asCompatibleSubstituteFor("apache/kafka"));

  @Container
  static final GenericContainer<?> VALKEY = new GenericContainer<>("mirror.gcr.io/valkey/valkey:8-alpine")
      .withExposedPorts(6379);

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.data.redis.host", VALKEY::getHost);
    r.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
  }

  @Autowired
  StringRedisTemplate redis;

  @Test
  void dangXuatMoiPhienVaDangXuatMotPhienDuocGhiVaoDanhSachThuHoi() throws Exception {
    UUID user = UUID.randomUUID();
    Instant at = Instant.parse("2026-10-22T01:00:00Z");
    try (var producer = producer()) {
      producer.send(record(event("ADMIN_LOGOUT_USER", user, null), TENANT, at)).get();
      producer.send(record(event("LOGOUT", user, "phien-1"), TENANT, at)).get();
      producer.send(record(event("LOGIN", user, "phien-2"), TENANT, at)).get();
      producer.send(record(event("ADMIN_LOGOUT_USER", UUID.randomUUID(), null), "khong-phai-uuid", at)).get();
      producer.send(new ProducerRecord<>(EventTopics.IAM_AUTH_EVENT_V1, "x", new byte[] {1, 2, 3})).get();
    }
    String userKey = RevocationListener.USER_PREFIX + TENANT + ":" + user;
    String sidKey = RevocationListener.SID_PREFIX + TENANT + ":phien-1";
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      assertThat(redis.opsForValue().get(userKey)).isEqualTo(Long.toString(at.getEpochSecond()));
      assertThat(redis.opsForValue().get(sidKey)).isEqualTo(Long.toString(at.getEpochSecond()));
    });
    assertThat(redis.getExpire(userKey)).isBetween(Duration.ofHours(9).toSeconds(), Duration.ofHours(10).toSeconds());
    assertThat(redis.hasKey(RevocationListener.SID_PREFIX + TENANT + ":phien-2")).isFalse();
    assertThat(redis.keys("mission:revoked:*")).hasSize(2);
  }

  private static AuthEvent event(String type, UUID user, String sid) {
    return AuthEvent.newBuilder().setEventType(type).setUserId(user).setClientId("mission-web").setSessionId(sid)
        .setError(null).build();
  }

  private static ProducerRecord<String, byte[]> record(AuthEvent event, String tenant, Instant at) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
    new SpecificDatumWriter<>(AuthEvent.class).write(event, enc);
    enc.flush();
    var rec = new ProducerRecord<>(EventTopics.IAM_AUTH_EVENT_V1, String.valueOf(event.getUserId()), out.toByteArray());
    rec.headers().add("tenantid", tenant.getBytes(StandardCharsets.UTF_8));
    rec.headers().add("occurredat", at.toString().getBytes(StandardCharsets.UTF_8));
    return rec;
  }

  private static KafkaProducer<String, byte[]> producer() {
    return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
        new StringSerializer(), new ByteArraySerializer());
  }
}
