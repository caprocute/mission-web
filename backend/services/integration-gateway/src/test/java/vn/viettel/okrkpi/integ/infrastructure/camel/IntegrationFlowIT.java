package vn.viettel.okrkpi.integ.infrastructure.camel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.ProducerTemplate;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import vn.viettel.okrkpi.integ.application.DeadLetterService;
import vn.viettel.okrkpi.integ.application.InboundPipeline;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.OutboundBinding;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * Khung integration-gateway với bộ kết nối mẫu giả lập (DV-13-01): PostgreSQL 17 có RLS với vai trò
 * {@code svc_integ}, Kafka thật cho consumer chiều ra. Thông điệp trùng chỉ xử lý một lần; lỗi 5
 * lần vào thư chết rồi chạy lại được; mạch mở sau 50% lỗi trong 20 lời gọi; nguyên văn chỉ lưu bản
 * mã và không lên nhật ký; chiều ra chống trùng, chống vòng lặp, thử lại tới thư chết.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.integ.route.redelivery-delay=PT0.01S",
      "okrkpi.integ.outbound.dispatch.period=PT1H",
      "okrkpi.integ.outbound.consumer.enabled=true"
    })
class IntegrationFlowIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000013a001");
  static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-00000013a002");
  static final UUID CONNECTOR = UUID.fromString("0192f1a2-0000-7000-8000-00000013a003");
  static final String TOPIC = "test.outbound.v1";
  static final String SECRET_MARKER = "NOI-DUNG-MAT-0192";

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

  static {
    POSTGRES.start();
    KAFKA.start();
    migrate();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=integ");
    r.add("spring.datasource.username", () -> "svc_integ");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add("mission.service.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  static void migrate() {
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.CELL_SHARED)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin = admin()) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.CELL_SHARED);
      new SchemaMigrator(MigrationOptions.defaults())
          .migrate(
              POSTGRES.getJdbcUrl(),
              "integ",
              new MigrationCredentials.Credentials("mig_integ", PASSWORD));
      admin
          .createStatement()
          .execute(
              "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
                  + " protocol, endpoint, secret_path, direction, enabled) VALUES ('"
                  + CONNECTOR
                  + "', '"
                  + TENANT
                  + "', gen_random_uuid(), gen_random_uuid(), 'MAU', 'REST',"
                  + " 'https://mau.invalid', 'kv/integ/mau', 'HAI_CHIEU', true)");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @TestConfiguration
  static class Fakes {

    @Bean
    SampleConnector sampleConnector() {
      return new SampleConnector();
    }

    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    /** Bản mã thử: đảo bit từng byte, đủ để chứng minh cột không chứa bản rõ. */
    @Bean
    @Primary
    PayloadCipher fakeCipher() {
      return new PayloadCipher() {
        @Override
        public Sealed seal(TenantId tenant, String column, UUID rowId, byte[] plain) {
          return new Sealed(
              flip(plain), UUID.nameUUIDFromBytes(column.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public byte[] open(TenantId tenant, String column, UUID rowId, byte[] ciphertext) {
          return flip(ciphertext);
        }
      };
    }

    @Bean
    SampleSender sampleSender() {
      return new SampleSender();
    }

    @Bean
    OutboundBinding sampleBinding() {
      return new OutboundBinding() {
        @Override
        public String topic() {
          return TOPIC;
        }

        @Override
        public String connectorCode() {
          return SampleConnector.CODE;
        }

        @Override
        public String messageType() {
          return "KET_QUA_XEP_LOAI";
        }

        @Override
        public byte[] format(byte[] eventPayload, Map<String, String> headers) {
          return ("<ketQua>" + eventPayload.length + "</ketQua>").getBytes(StandardCharsets.UTF_8);
        }
      };
    }

    /** Route thử circuit breaker của khung: lời gọi lẻ lỗi, chẵn thành công. */
    @Bean
    IntegRouteBuilder breakerProbeRoute(RouteSettings settings, BreakerProbe probe) {
      return new IntegRouteBuilder(settings) {
        @Override
        public void configure() {
          var route = from("direct:breaker-probe").routeId("breaker-probe");
          breaker(route, "breaker-probe-cb", 2)
              .process(
                  e -> {
                    if (probe.calls.incrementAndGet() % 2 == 1) {
                      throw new IOException("Đối tác lỗi");
                    }
                    e.getMessage().setBody("ok");
                  })
              .onFallback()
              .setBody(constant("fallback"))
              .end();
        }
      };
    }

    @Bean
    BreakerProbe breakerProbe() {
      return new BreakerProbe();
    }
  }

  static class BreakerProbe {
    final AtomicInteger calls = new AtomicInteger();
  }

  static class SampleSender implements OutboundSender {
    final AtomicBoolean fail = new AtomicBoolean();
    final AtomicInteger sent = new AtomicInteger();

    @Override
    public String connectorCode() {
      return SampleConnector.CODE;
    }

    @Override
    public Optional<String> send(
        Connector connector, UUID messageId, String messageType, byte[] payload)
        throws IOException {
      sent.incrementAndGet();
      if (fail.get()) {
        throw new IOException("Đối tác từ chối");
      }
      return Optional.of("ACK-" + messageId);
    }
  }

  static byte[] flip(byte[] in) {
    byte[] out = in.clone();
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) ~out[i];
    }
    return out;
  }

  @Autowired SampleConnector sample;
  @Autowired SampleSender sender;
  @Autowired BreakerProbe probe;
  @Autowired ProducerTemplate camel;
  @Autowired InboundPipeline inbound;
  @Autowired OutboundPipeline outbound;
  @Autowired DeadLetterService deadLetters;
  @Autowired MappingXrefService mappings;

  @Value("${local.management.port}")
  int managementPort;

  final TenantId tenant = TenantId.of(TENANT);

  JdbcTemplate adminJdbc() {
    org.springframework.jdbc.datasource.DriverManagerDataSource ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    return new JdbcTemplate(ds);
  }

  int count(String sql, Object... args) {
    Integer n = adminJdbc().queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void thongDiepTrungChiXuLyMotLanVaNguyenVanChiLaBanMa(CapturedOutput output) {
    sample.pages.add(
        List.of(
            SampleConnector.message("vb-1", SECRET_MARKER + " chỉ thị số 1"),
            SampleConnector.message("vb-1", SECRET_MARKER + " chỉ thị số 1")));
    sample.pages.add(List.of(SampleConnector.message("vb-1", SECRET_MARKER + " gửi lại")));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                count(
                        "SELECT count(*) FROM integ.sync_cursor WHERE connector_id = ?"
                            + " AND cursor_value::int >= ?",
                        CONNECTOR,
                        sample.pages.size())
                    == 1);

    assertThat(count("SELECT count(*) FROM integ.inbox_message WHERE external_msg_id = 'vb-1'"))
        .isEqualTo(1);
    assertThat(sample.translations.get("vb-1").get()).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM integ.inbox_message WHERE external_msg_id = 'vb-1'"
                    + " AND status = 'DA_PHAT' AND emitted_event_id IS NOT NULL"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM integ.outbox_event WHERE topic = 'integ.document.received.v1'"
                    + " AND aggregate_id = (SELECT internal_id FROM integ.mapping_xref"
                    + " WHERE external_id = 'vb-1')"))
        .isEqualTo(1);
    byte[] raw =
        adminJdbc()
            .queryForObject(
                "SELECT raw_enc FROM integ.inbox_message WHERE external_msg_id = 'vb-1'",
                byte[].class);
    assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain(SECRET_MARKER);
    assertThat(output.getAll()).doesNotContain(SECRET_MARKER);
  }

  @Test
  void loiNamLanVaoThuChetRoiChayLaiDuoc(CapturedOutput output) {
    sample.failing.add("vb-loi");
    sample.pages.add(
        List.of(
            SampleConnector.message("vb-loi", SECRET_MARKER + " hỏng"),
            SampleConnector.message("vb-tot", "bình thường")));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                count(
                        "SELECT count(*) FROM integ.dead_letter d JOIN integ.inbox_message m"
                            + " ON m.id = d.ref_message_id WHERE m.external_msg_id = 'vb-loi'")
                    == 1);
    assertThat(sample.translations.get("vb-loi").get()).isEqualTo(6);
    assertThat(
            count(
                "SELECT count(*) FROM integ.inbox_message WHERE external_msg_id = 'vb-loi'"
                    + " AND status = 'LOI'"))
        .isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                count(
                        "SELECT count(*) FROM integ.inbox_message WHERE external_msg_id = 'vb-tot'"
                            + " AND status = 'DA_PHAT'")
                    == 1);

    var letter =
        deadLetters.list(tenant, CONNECTOR, true, null, null, 200).stream()
            .filter(l -> l.direction().equals(DeadLetterService.INBOUND))
            .findFirst()
            .orElseThrow();
    assertThat(letter.errorCode()).isEqualTo("IllegalArgumentException");
    assertThat(letter.errorDetail()).doesNotContain(SECRET_MARKER);

    sample.failing.remove("vb-loi");
    deadLetters.replay(tenant, letter.id(), ACTOR);
    assertThat(
            count(
                "SELECT count(*) FROM integ.inbox_message WHERE external_msg_id = 'vb-loi'"
                    + " AND status = 'DA_PHAT'"))
        .isEqualTo(1);
    assertThat(deadLetters.list(tenant, CONNECTOR, true, null, null, 200))
        .noneMatch(l -> l.id().equals(letter.id()));
    assertThatThrownBy(() -> deadLetters.resolve(tenant, letter.id(), ACTOR))
        .isInstanceOf(IllegalStateException.class);
    assertThat(output.getAll()).doesNotContain(SECRET_MARKER);
  }

  @Test
  void machMoSauNamMuoiPhanTramLoiTrongHaiMuoiLoiGoi() {
    for (int i = 0; i < 20; i++) {
      camel.requestBody("direct:breaker-probe", "x");
    }
    assertThat(probe.calls.get()).isEqualTo(20);
    assertThat(camel.requestBody("direct:breaker-probe", "x")).isEqualTo("fallback");
    assertThat(probe.calls.get()).as("mạch mở thì không gọi đối tác").isEqualTo(20);
  }

  @Test
  void chieuRaChongTrungChongVongLapVaThuLaiToiThuChet() throws Exception {
    UUID event = UUID.randomUUID();
    try (KafkaProducer<byte[], byte[]> producer = producer()) {
      for (int i = 0; i < 2; i++) {
        ProducerRecord<byte[], byte[]> rec =
            new ProducerRecord<>(
                TOPIC, ("k" + i).getBytes(StandardCharsets.UTF_8), new byte[] {9, 9});
        rec.headers().add("tenantid", TENANT.toString().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("id", event.toString().getBytes(StandardCharsets.UTF_8));
        producer.send(rec).get();
      }
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                count(
                        "SELECT count(*) FROM integ.outbound_message WHERE source_event_id = ?",
                        event)
                    == 1);
    Thread.sleep(500);
    assertThat(
            count("SELECT count(*) FROM integ.outbound_message WHERE source_event_id = ?", event))
        .isEqualTo(1);
    Map<String, Object> row =
        adminJdbc()
            .queryForMap(
                "SELECT id, created_day, payload_enc FROM integ.outbound_message"
                    + " WHERE source_event_id = ?",
                event);
    assertThat(new String((byte[]) row.get("payload_enc"), StandardCharsets.UTF_8))
        .doesNotContain("ketQua");
    UUID id = (UUID) row.get("id");
    LocalDate day = ((java.sql.Date) row.get("created_day")).toLocalDate();
    assertThat(outbound.dispatch(tenant, day, id)).contains(OutboundStatus.DA_XAC_NHAN);
    assertThat(
            adminJdbc()
                .queryForObject(
                    "SELECT ack_ref FROM integ.outbound_message WHERE id = ?", String.class, id))
        .isEqualTo("ACK-" + id);

    // Thử lại tới thư chết.
    sender.fail.set(true);
    var queued =
        outbound.enqueue(
            tenant, SampleConnector.CODE, UUID.randomUUID(), "KET_QUA_XEP_LOAI", new byte[] {1});
    assertThat(queued.result()).isEqualTo(OutboundPipeline.Result.QUEUED);
    OutboundStatus last = null;
    for (int i = 0; i < 20 && last != OutboundStatus.LOI; i++) {
      adminJdbc()
          .update(
              "UPDATE integ.outbound_message SET next_attempt_at = now() - interval '1 second'"
                  + " WHERE id = ?",
              queued.id());
      last = outbound.dispatch(tenant, LocalDate.now(), queued.id()).orElse(last);
    }
    assertThat(last).isEqualTo(OutboundStatus.LOI);
    assertThat(count("SELECT attempts FROM integ.outbound_message WHERE id = ?", queued.id()))
        .isEqualTo(5);
    var letter =
        deadLetters.list(tenant, CONNECTOR, true, null, null, 200).stream()
            .filter(l -> l.refMessageId().equals(queued.id()))
            .findFirst()
            .orElseThrow();
    assertThat(letter.direction()).isEqualTo(DeadLetterService.OUTBOUND);
    sender.fail.set(false);
    deadLetters.replay(tenant, letter.id(), ACTOR);
    assertThat(outbound.dispatch(tenant, LocalDate.now(), queued.id()))
        .contains(OutboundStatus.DA_XAC_NHAN);

    // Chống vòng lặp: sự kiện do chính gateway phát từ thông điệp vào của MAU không gửi lại MAU.
    var receipt =
        inbound.receive(tenant, SampleConnector.CODE, SampleConnector.message("vb-vong", "x"));
    UUID emitted = receipt.eventIds().get(0);
    assertThat(
            outbound
                .enqueue(tenant, SampleConnector.CODE, emitted, "KET_QUA_XEP_LOAI", new byte[] {1})
                .result())
        .isEqualTo(OutboundPipeline.Result.ECHO);
  }

  @Test
  void anhXaMaNgoaiOnDinhVaGanLaiKhiHeNgoaiTaiSuDungMa() {
    LocalDate d1 = LocalDate.of(2026, 1, 1);
    UUID first =
        mappingCall(
            () ->
                mappings.resolveOrCreate(
                    tenant, CONNECTOR, EntityType.DON_VI, "ORG-7", d1, "đồng bộ"));
    assertThat(
            mappingCall(
                () ->
                    mappings.resolveOrCreate(
                        tenant, CONNECTOR, EntityType.DON_VI, "ORG-7", d1, null)))
        .isEqualTo(first);
    UUID other = UUID.randomUUID();
    LocalDate d2 = LocalDate.of(2026, 6, 1);
    assertThat(
            mappingCall(
                () ->
                    mappings.link(tenant, CONNECTOR, EntityType.DON_VI, "ORG-7", other, d2, null)))
        .isTrue();
    assertThat(
            mappingCall(
                () ->
                    mappings.link(tenant, CONNECTOR, EntityType.DON_VI, "ORG-7", other, d2, null)))
        .isFalse();
    assertThat(
            mappingCall(() -> mappings.resolve(tenant, CONNECTOR, EntityType.DON_VI, "ORG-7", d1)))
        .contains(first);
    assertThat(
            mappingCall(() -> mappings.resolve(tenant, CONNECTOR, EntityType.DON_VI, "ORG-7", d2)))
        .contains(other);
    assertThat(
            mappingCall(() -> mappings.externalOf(tenant, CONNECTOR, EntityType.DON_VI, other, d2)))
        .contains("ORG-7");
  }

  @Autowired org.springframework.transaction.support.TransactionTemplate integTransactions;

  <T> T mappingCall(java.util.function.Supplier<T> work) {
    Object[] out = new Object[1];
    vn.viettel.okrkpi.shared.tenant.TenantContext.run(
        tenant, () -> out[0] = integTransactions.execute(s -> work.get()));
    @SuppressWarnings("unchecked")
    T v = (T) out[0];
    return v;
  }

  @Test
  void probeTrenCongQuanTri() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    for (String probe : List.of("liveness", "readiness")) {
      var res =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:" + managementPort + "/actuator/health/" + probe))
                  .build(),
              BodyHandlers.ofString());
      assertThat(res.statusCode()).as(probe).isEqualTo(200);
    }
  }

  static KafkaProducer<byte[], byte[]> producer() {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    return new KafkaProducer<>(p);
  }
}
