package vn.viettel.okrkpi.integ.infrastructure.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.integ.application.alert.OverdueAlertService;
import vn.viettel.okrkpi.integ.application.anchor.AnchorService;
import vn.viettel.okrkpi.integ.application.anchor.SealNotice;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventVerifier;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsHttpSender;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;
import vn.vtokr.ledger.v1.SoCaiDaNiemPhong;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * DV-13-09 với PostgreSQL 17 (RLS, vai trò {@code svc_integ}), Kafka thật, ledger-service và các
 * đích neo, CSDL CBCCVC giả lập (YC-PT-13): một ngày niêm phong tạo đúng một bản neo cho mỗi đích,
 * đích lỗi rồi phục hồi được gửi lại, biên nhận chuyển cho ledger-service đúng một lần mỗi đích,
 * ngày thiếu biên nhận được cảnh báo một lần; EVT-12 giao lại chỉ sinh một thông điệp, có chữ ký
 * JWS kiểm được, không có dữ liệu C2, bản thay thế mang tham chiếu bản trước.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.integ.pull.enabled=false",
      "okrkpi.integ.route.redelivery-delay=PT0.05S",
      "okrkpi.integ.outbound.dispatch.period=PT0.2S",
      "okrkpi.integ.outbound.consumer.enabled=true",
      "okrkpi.integ.outbound.ack-timeout=PT0.5S",
      "okrkpi.integ.cloudevents.cell=cell-hcm",
      "okrkpi.integ.cloudevents.connectors[NEO_KHO].mtls=false",
      "okrkpi.integ.cloudevents.connectors[NEO_TSA].mtls=false",
      "okrkpi.integ.cloudevents.connectors[NEO_CELL].mtls=false",
      "okrkpi.integ.cloudevents.connectors[CBCCVC].mtls=false",
      "okrkpi.integ.anchor.enabled=true",
      "okrkpi.integ.anchor.operator-tenant=0192f1a2-0000-7000-8000-0000001309a0",
      "okrkpi.integ.anchor.targets[KHO_VPTW]=NEO_KHO",
      "okrkpi.integ.anchor.targets[TSA_DOC_LAP]=NEO_TSA",
      "okrkpi.integ.anchor.targets[CELL_KHAC]=NEO_CELL",
      "okrkpi.integ.anchor.grace=P2D",
      "okrkpi.integ.anchor.fetch-retry=PT0.2S",
      "okrkpi.integ.anchor.period=PT0.2S",
      "okrkpi.integ.rating-share.enabled=true",
      "okrkpi.integ.alerts.recipients=0192f1a2-0000-7000-8000-0000001309ff",
      "okrkpi.integ.alerts.overdue.enabled=false"
    })
class AnchorAndRatingShareIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID OPERATOR = UUID.fromString("0192f1a2-0000-7000-8000-0000001309a0");
  static final UUID TENANT_A = UUID.fromString("0192f1a2-0000-7000-8000-0000001309a1");
  static final UUID TENANT_B = UUID.fromString("0192f1a2-0000-7000-8000-0000001309a2");
  static final UUID ADMIN = UUID.fromString("0192f1a2-0000-7000-8000-0000001309ff");
  static final UUID CBCCVC = UUID.fromString("0192f1a2-0000-7000-8000-0000001309c1");
  static final UUID PERSON = UUID.fromString("0192f1a2-0000-7000-8000-0000001309d1");
  static final UUID DECIDER = UUID.fromString("0192f1a2-0000-7000-8000-0000001309d2");
  static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
  static final ObjectMapper JSON = new ObjectMapper();

  /** Nửa đêm vừa qua theo giờ Việt Nam: cuối cửa sổ của niêm phong cuối ngày. */
  static final Instant MIDNIGHT =
      LocalDate.now(HCM).atStartOfDay(HCM).toInstant().truncatedTo(ChronoUnit.SECONDS);

  static final UUID CELL_SEAL = UUID.fromString("0192f1a2-0000-7000-8000-0000001309e1");

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_neo");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
  static final ECKey KEY;
  static final Path JWKS;
  static final HttpServer PEERS;

  /** Số lần ledger-service được hỏi gói neo; lần đầu trả 404 (chưa dựng). */
  static final AtomicInteger LEDGER_ASKS = new AtomicInteger();

  /** Biên nhận ledger-service nhận theo (cell_seal_id, anchor_target). */
  static final Map<String, AtomicInteger> LEDGER_RECEIPTS = new ConcurrentHashMap<>();

  /** Đích TSA lỗi hai lần đầu. */
  static final AtomicInteger TSA_FAILURES = new AtomicInteger(2);

  static final Map<String, AtomicInteger> ANCHOR_POSTS = new ConcurrentHashMap<>();
  static final List<Received> CBCCVC_POSTS = new CopyOnWriteArrayList<>();
  static final List<SpecificRecord> OUTBOX = Collections.synchronizedList(new ArrayList<>());
  static final AtomicBoolean LEDGER_DOWN_FOR_OLD = new AtomicBoolean(true);

  record Received(byte[] body, String signature, String idempotencyKey) {}

  static {
    try {
      KEY = new ECKeyGenerator(Curve.P_256).keyID("cell-hcm-2026").generate();
      JWKS = Files.createTempFile("integ-neo", ".jwks.json");
      Files.writeString(JWKS, new JWKSet(KEY).toString(false));
      PEERS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      PEERS.createContext("/internal/v1/ledger/cell-anchors", AnchorAndRatingShareIT::cellAnchor);
      PEERS.createContext("/internal/v1/ledger/anchor-receipts", AnchorAndRatingShareIT::receipt);
      PEERS.createContext("/neo/", AnchorAndRatingShareIT::anchorTarget);
      PEERS.createContext("/ndxp/xep-loai", AnchorAndRatingShareIT::cbccvc);
      PEERS.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
    r.add("okrkpi.integ.cloudevents.signing-jwks-file", JWKS::toString);
    r.add("okrkpi.integ.anchor.ledger-url", () -> peer(""));
  }

  static String peer(String path) {
    return "http://127.0.0.1:" + PEERS.getAddress().getPort() + path;
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
      connector(admin, UUID.randomUUID(), OPERATOR, "NEO_KHO", peer("/neo/kho"));
      connector(admin, UUID.randomUUID(), OPERATOR, "NEO_TSA", peer("/neo/tsa"));
      connector(admin, UUID.randomUUID(), OPERATOR, "NEO_CELL", peer("/neo/cell"));
      connector(admin, CBCCVC, TENANT_A, "CBCCVC", peer("/ndxp/xep-loai"));
      for (Object[] m : new Object[][] {{PERSON, "079085001234"}, {DECIDER, "079078004321"}}) {
        admin
            .createStatement()
            .execute(
                "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type,"
                    + " external_id, internal_id, valid_from) VALUES (gen_random_uuid(), '"
                    + TENANT_A
                    + "', '"
                    + CBCCVC
                    + "', 'NHAN_SU', '"
                    + m[1]
                    + "', '"
                    + m[0]
                    + "', DATE '2020-01-01')");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void connector(Connection admin, UUID id, UUID tenant, String code, String endpoint)
      throws SQLException {
    admin
        .createStatement()
        .execute(
            "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
                + " protocol, endpoint, secret_path, direction, enabled) VALUES ('"
                + id
                + "', '"
                + tenant
                + "', gen_random_uuid(), gen_random_uuid(), '"
                + code
                + "', 'REST', '"
                + endpoint
                + "', 'kv/integ/"
                + code.toLowerCase(Locale.ROOT)
                + "', 'RA', true)");
  }

  static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  // ------------------------------------------------------------------ hệ ngoài giả lập

  static void cellAnchor(HttpExchange x) throws IOException {
    String query = x.getRequestURI().getRawQuery();
    Instant windowEnd =
        Instant.parse(
            java.net.URLDecoder.decode(
                query.substring(query.indexOf("windowEnd=") + 10), StandardCharsets.UTF_8));
    if (!windowEnd.equals(MIDNIGHT) || LEDGER_ASKS.getAndIncrement() == 0) {
      respond(x, 404, new byte[0]);
      return;
    }
    String body =
        """
        {"cellSealId":"%s","cellCode":"cell-hcm","windowStart":"%s","windowEnd":"%s",
         "cellRoot":"%s","prevCellRoot":"%s","signature":"c2lnbmF0dXJl",
         "signerCertSha256":"ab12","tsaToken":"dHNhLXRva2Vu","tsaGenTime":"%s"}
        """
            .formatted(
                CELL_SEAL,
                MIDNIGHT.minus(Duration.ofDays(1)),
                MIDNIGHT,
                "11".repeat(32),
                "22".repeat(32),
                MIDNIGHT.plusSeconds(5));
    respond(x, 200, body.getBytes(StandardCharsets.UTF_8));
  }

  static void receipt(HttpExchange x) throws IOException {
    JsonNode n = JSON.readTree(x.getRequestBody().readAllBytes());
    String key = n.path("cellSealId").asText() + "/" + n.path("anchorTarget").asText();
    int seen = LEDGER_RECEIPTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    respond(x, seen == 1 ? 201 : 409, new byte[0]);
  }

  static void anchorTarget(HttpExchange x) throws IOException {
    String target = x.getRequestURI().getPath().substring("/neo/".length());
    ANCHOR_POSTS.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
    x.getRequestBody().readAllBytes();
    if ("tsa".equals(target) && TSA_FAILURES.getAndDecrement() > 0) {
      respond(x, 503, new byte[0]);
      return;
    }
    respond(x, 200, ("bien-nhan-da-ky:" + target).getBytes(StandardCharsets.UTF_8));
  }

  static void cbccvc(HttpExchange x) throws IOException {
    CBCCVC_POSTS.add(
        new Received(
            x.getRequestBody().readAllBytes(),
            x.getRequestHeaders().getFirst("X-JWS-Signature"),
            x.getRequestHeaders().getFirst("Idempotency-Key")));
    x.getResponseHeaders().add(CloudEventsHttpSender.ACK_HEADER, "NDXP-" + CBCCVC_POSTS.size());
    respond(x, 200, new byte[0]);
  }

  static void respond(HttpExchange x, int status, byte[] body) throws IOException {
    x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      x.getResponseBody().write(body);
    }
    x.close();
  }

  // ------------------------------------------------------------------ cấu hình thử

  @TestConfiguration
  static class Fakes {

    /** Ghi lại tải trọng outbox (EVT-21) để kiểm, không cần registry Avro. */
    @Bean
    OutboxPayloadSerializer recordingSerializer() {
      return (topic, payload) -> {
        OUTBOX.add(payload);
        return new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
      };
    }

    @Bean
    @Primary
    PayloadCipher fakeCipher() {
      return new PayloadCipher() {
        @Override
        public Sealed seal(TenantId tenant, String column, UUID rowId, byte[] plain) {
          return new Sealed(
              plain.clone(), UUID.nameUUIDFromBytes(column.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public byte[] open(TenantId tenant, String column, UUID rowId, byte[] ciphertext) {
          return ciphertext.clone();
        }
      };
    }
  }

  @Autowired AnchorService anchors;
  @Autowired OverdueAlertService overdue;

  /** Kiểm số liệu bằng tài khoản quản trị (không chịu RLS). */
  final JdbcTemplate jdbc =
      new JdbcTemplate(
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

  // ------------------------------------------------------------------ bản neo

  @Test
  void motNgayNiemPhongTaoMotBanNeoChoMoiDichVaChuyenBienNhanDungMotLan() throws Exception {
    UUID first = UUID.randomUUID();
    try (KafkaProducer<byte[], byte[]> p = producer()) {
      // Hai tenant cùng cell niêm phong cuối ngày, một sự kiện giao lại, một niêm phong giữa ngày.
      send(p, EventTopics.EVT_19_SO_CAI_DA_NIEM_PHONG, first, TENANT_A, seal("HOURLY", MIDNIGHT));
      send(p, EventTopics.EVT_19_SO_CAI_DA_NIEM_PHONG, first, TENANT_A, seal("HOURLY", MIDNIGHT));
      send(
          p,
          EventTopics.EVT_19_SO_CAI_DA_NIEM_PHONG,
          UUID.randomUUID(),
          TENANT_B,
          seal("HOURLY", MIDNIGHT));
      send(
          p,
          EventTopics.EVT_19_SO_CAI_DA_NIEM_PHONG,
          UUID.randomUUID(),
          TENANT_B,
          seal("HOURLY", MIDNIGHT.minus(Duration.ofHours(5))));
    }

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                assertThat(LEDGER_RECEIPTS)
                    .containsOnlyKeys(
                        CELL_SEAL + "/KHO_VPTW",
                        CELL_SEAL + "/TSA_DOC_LAP",
                        CELL_SEAL + "/CELL_KHAC"));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integ.anchor_window WHERE tenant_id = ? AND window_end = ?",
                Integer.class,
                OPERATOR,
                java.sql.Timestamp.from(MIDNIGHT)))
        .as("một ngày của cell chỉ một dòng dù nhiều tenant và giao lại")
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integ.outbound_message WHERE tenant_id = ?",
                Integer.class,
                OPERATOR))
        .as("một thông điệp ra cho mỗi đích")
        .isEqualTo(3);
    assertThat(LEDGER_ASKS.get()).as("lần hỏi đầu chưa có gói neo, hỏi lại").isGreaterThan(1);
    assertThat(ANCHOR_POSTS.get("tsa").get()).as("đích lỗi được gửi lại").isEqualTo(3);
    assertThat(ANCHOR_POSTS.get("kho").get()).isEqualTo(1);

    // Chạy thêm vài vòng chuyển: không có biên nhận nào được chuyển lần nữa.
    for (int i = 0; i < 3; i++) {
      anchors.relayReceipts(50);
    }
    LEDGER_RECEIPTS.values().forEach(c -> assertThat(c.get()).isEqualTo(1));
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT d.anchor_target, d.receipt, d.relayed_at, m.status, m.ack_ref"
                + " FROM integ.anchor_dispatch d JOIN integ.outbound_message m"
                + " ON m.tenant_id = d.tenant_id AND m.id = d.outbound_message_id"
                + " WHERE d.tenant_id = ?",
            OPERATOR);
    assertThat(rows).hasSize(3);
    for (Map<String, Object> r : rows) {
      assertThat(r.get("relayed_at")).isNotNull();
      assertThat(r.get("status")).isEqualTo("DA_XAC_NHAN");
      assertThat(new String((byte[]) r.get("receipt"), StandardCharsets.UTF_8))
          .startsWith("bien-nhan-da-ky:");
      assertThat((String) r.get("ack_ref")).startsWith("sha256:");
    }
  }

  @Test
  void ngayChuaDuBienNhanDuocCanhBaoMotLan() {
    Instant old = MIDNIGHT.minus(Duration.ofDays(3));
    assertThat(
            anchors.onSeal(
                new SealNotice(UUID.randomUUID(), UUID.randomUUID(), "HOURLY", null, old)))
        .isTrue();
    assertThat(
            anchors.onSeal(
                new SealNotice(UUID.randomUUID(), UUID.randomUUID(), "PERIOD_CLOSE", null, old)))
        .as("loại niêm phong không phải bản neo trong ngày")
        .isFalse();
    String day = old.atZone(HCM).toLocalDate().minusDays(1).toString();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT checked_at IS NOT NULL FROM integ.anchor_window"
                                + " WHERE tenant_id = ? AND window_end = ?",
                            Boolean.class,
                            OPERATOR,
                            java.sql.Timestamp.from(old)))
                    .isTrue());
    assertThat(anchors.checkShortfalls(50)).as("đã cảnh báo, không báo lại").isZero();
    List<YeuCauThongBao> alerts =
        OUTBOX.stream()
            .filter(YeuCauThongBao.class::isInstance)
            .map(YeuCauThongBao.class::cast)
            .filter(y -> y.getTemplateCode().equals("NEO_SO_CAI_THIEU_BIEN_NHAN"))
            .filter(y -> y.getParams().contains(day))
            .toList();
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0).getRecipientIds()).containsExactly(ADMIN);
    assertThat(alerts.get(0).getParams()).contains("\"bienNhan\":0").contains("\"toiThieu\":2");
  }

  // ------------------------------------------------------------------ EVT-12

  @Test
  void quyetDinhXepLoaiGiaoLaiChiGuiMotLanCoChuKyKiemDuocVaBanThayTheThamChieuBanTruoc()
      throws Exception {
    UUID profile = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID sign = UUID.randomUUID();
    try (KafkaProducer<byte[], byte[]> p = producer()) {
      send(p, EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH, first, TENANT_A, rating(profile, sign, 1));
      send(p, EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH, first, TENANT_A, rating(profile, sign, 1));
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> posts(first).size() == 1);

    Received r = posts(first).get(0);
    JsonNode ce =
        new CloudEventVerifier(new JWKSet(KEY.toPublicJWK()).toString(), Map.of())
            .verifyDetached(r.body(), r.signature());
    assertThat(ce.path("type").asText()).isEqualTo("vn.viettel.okrkpi.appraisal.rating.decided.v1");
    assertThat(ce.path("classification").asText()).isEqualTo("C1");
    assertThat(ce.path("subject").asText()).isEqualTo("appraisal-profile/" + profile);
    assertThat(ce.at("/data/subjectRef/scheme").asText()).isEqualTo("cbccvc");
    assertThat(ce.at("/data/subjectRef/id").asText()).isEqualTo("079085001234");
    assertThat(ce.at("/data/decidedBy/id").asText()).isEqualTo("079078004321");
    assertThat(ce.at("/data/signatureRef").asText()).isEqualTo("esign:" + sign);
    assertThat(ce.path("data").has("supersedes")).isFalse();
    String text = new String(r.body(), StandardCharsets.UTF_8);
    assertThat(text).doesNotContain(PERSON.toString()).doesNotContain(DECIDER.toString());

    // Hậu kiểm thay quyết định: bản mới mang tham chiếu bản trước; bản cũ đến muộn bị bỏ qua.
    UUID second = UUID.randomUUID();
    UUID stale = UUID.randomUUID();
    try (KafkaProducer<byte[], byte[]> p = producer()) {
      send(
          p, EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH, second, TENANT_A, rating(profile, sign, 2));
      send(p, EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH, stale, TENANT_A, rating(profile, sign, 1));
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> posts(second).size() == 1);
    JsonNode replaced = JSON.readTree(posts(second).get(0).body());
    assertThat(replaced.at("/data/supersedes").asText()).isEqualTo(first.toString());
    Thread.sleep(1500);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integ.outbound_message WHERE tenant_id = ?"
                    + " AND connector_id = ? AND source_event_id IN (?, ?, ?)",
                Integer.class,
                TENANT_A,
                CBCCVC,
                first,
                second,
                stale))
        .as("hai quyết định, mỗi quyết định một thông điệp; bản cũ đến muộn không gửi")
        .isEqualTo(2);
  }

  @Test
  void thongDiepQuaHanThuLaiDuocCanhBaoMotLan() throws Exception {
    UUID profile = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    try (KafkaProducer<byte[], byte[]> p = producer()) {
      send(p, EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH, event, TENANT_A, rating(profile, null, 1));
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> posts(event).size() == 1);
    try (Connection admin = admin()) {
      admin
          .createStatement()
          .execute(
              "UPDATE integ.outbound_message SET status = 'LOI', ack_ref = NULL"
                  + " WHERE source_event_id = '"
                  + event
                  + "'");
    }
    OUTBOX.clear();
    assertThat(overdue.scan(100)).isEqualTo(1);
    assertThat(overdue.scan(100)).isZero();
    assertThat(
            OUTBOX.stream()
                .filter(YeuCauThongBao.class::isInstance)
                .map(o -> ((YeuCauThongBao) o).getTemplateCode()))
        .containsExactly("THONG_DIEP_RA_QUA_HAN");
  }

  // ------------------------------------------------------------------ tiện ích

  static List<Received> posts(UUID eventId) {
    List<Received> out = new ArrayList<>();
    for (Received r : CBCCVC_POSTS) {
      try {
        if (JSON.readTree(r.body()).path("id").asText().equals(eventId.toString())) {
          out.add(r);
        }
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return out;
  }

  static SoCaiDaNiemPhong seal(String kind, Instant windowEnd) {
    return SoCaiDaNiemPhong.newBuilder()
        .setSealId(UUID.randomUUID())
        .setSealKind(kind)
        .setWindowStart(windowEnd.minus(Duration.ofHours(1)))
        .setWindowEnd(windowEnd)
        .setMerkleRoot(java.nio.ByteBuffer.wrap(new byte[32]))
        .setPrevSealRoot(null)
        .setTsaSerial("TSA-1")
        .setAggregateVersion(1)
        .build();
  }

  static XepLoaiDaQuyetDinh rating(UUID profile, UUID sign, int version) {
    return XepLoaiDaQuyetDinh.newBuilder()
        .setProfileId(profile)
        .setPersonId(PERSON)
        .setFlow("NHA_NUOC")
        .setRatingCode(version == 1 ? "HTTNV" : "HTXSNV")
        .setScoreSnapshotId(UUID.randomUUID())
        .setDeciderId(DECIDER)
        .setSignRequestId(sign)
        .setAggregateVersion(version)
        .build();
  }

  static KafkaProducer<byte[], byte[]> producer() {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return new KafkaProducer<>(p);
  }

  @SuppressWarnings("unchecked")
  static <T extends SpecificRecord> void send(
      KafkaProducer<byte[], byte[]> producer, String topic, UUID id, UUID tenant, T payload)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
    new SpecificDatumWriter<>((Class<T>) payload.getClass()).write(payload, enc);
    enc.flush();
    ProducerRecord<byte[], byte[]> rec =
        new ProducerRecord<>(
            topic, tenant.toString().getBytes(StandardCharsets.UTF_8), out.toByteArray());
    rec.headers().add(OutboxHeaders.EVENT_ID, id.toString().getBytes(StandardCharsets.UTF_8));
    rec.headers().add(OutboxHeaders.TENANT_ID, tenant.toString().getBytes(StandardCharsets.UTF_8));
    rec.headers().add(OutboxHeaders.CLASSIFICATION, "C1".getBytes(StandardCharsets.UTF_8));
    rec.headers()
        .add(OutboxHeaders.OCCURRED_AT, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
    rec.headers()
        .add(
            OutboxHeaders.TRACEPARENT,
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                .getBytes(StandardCharsets.UTF_8));
    producer.send(rec).get();
  }
}
