package vn.viettel.okrkpi.integ.infrastructure.truc;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
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
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Organ;
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
 * DV-13-05 với Trục liên thông giả lập bằng WireMock (YC-PT-13): kéo văn bản đến qua X-Road (đúng
 * đơn vị nhận thành EVT-24, chưa ánh xạ vào thư chết), gửi văn bản ra qua cổng nội bộ cho MOD-06
 * (security server lỗi 503 thì thử lại, đã có biên nhận thì không gửi lại, bên nhận từ chối thì hết
 * lượt vào thư chết, nơi nhận chưa cấu hình trả 422) và worker chỉ gửi các mã Trục.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.integ.auth-enabled=false",
      "okrkpi.integ.voffice.enabled=false",
      "okrkpi.integ.hrm.enabled=false",
      "okrkpi.integ.route.redelivery-delay=PT0.01S",
      "okrkpi.integ.route.max-redeliveries=2",
      "okrkpi.integ.outbound.dispatch.period=PT0.2S",
      "okrkpi.integ.outbound.dispatch.include-codes=BTC_TW",
      "okrkpi.integ.outbound.consumer.enabled=false",
      "okrkpi.integ.truc.period=PT0.3S"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrucConnectorsIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000013f001");
  static final UUID TRUC = UUID.fromString("0192f1a2-0000-7000-8000-00000013f002");
  static final UUID BTC = UUID.fromString("0192f1a2-0000-7000-8000-00000013f003");
  static final UUID UNIT = UUID.fromString("0192f1a2-0000-7000-8000-00000013f010");
  static final String CLIENT = "VN/GOV/000.00.00.H01/MISSION";
  static final String SERVICE = "VN/GOV/000.00.00.G22/TRUC/edoc";
  static final String SVC_PATH = "/r1/" + SERVICE;

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_truc");
  static final WireMockServer XROAD = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    POSTGRES.start();
    XROAD.start();
    XROAD.stubFor(
        get(urlPathEqualTo(SVC_PATH + "/edocs"))
            .withQueryParam("since", equalTo("1970-01-01T00:00:00Z"))
            .withHeader("X-Road-Client", equalTo(CLIENT))
            .willReturn(
                okJson(
                    "{\"items\":["
                        + item("VB-1", "000.00.00.H01", "2026-10-23T01:00:00Z")
                        + ","
                        + item("VB-2", "000.00.00.X99", "2026-10-23T01:05:00Z")
                        + "]}")));
    XROAD.stubFor(
        get(urlPathEqualTo(SVC_PATH + "/edocs"))
            .withQueryParam("since", equalTo("2026-10-23T01:05:00Z"))
            .willReturn(okJson("{\"items\":[]}")));
    migrate();
  }

  static String item(String id, String unit, String at) {
    byte[] xml =
        Edxml.build(
            new EdxmlDocument(
                id,
                new Organ("000.00.00.G01", "Văn phòng Trung ương Đảng"),
                List.of(new Organ(unit, "Tỉnh uỷ")),
                "5",
                "CV-VPTW",
                null,
                LocalDate.of(2026, 10, 22),
                "Công văn",
                "Triển khai đánh giá cán bộ năm 2026",
                null,
                null,
                List.of(),
                false));
    return "{\"docId\":\"%s\",\"receivedAt\":\"%s\",\"edxml\":\"%s\"}"
        .formatted(id, at, Base64.getEncoder().encodeToString(xml));
  }

  @AfterAll
  static void stop() {
    XROAD.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=integ");
    r.add("spring.datasource.username", () -> "svc_integ");
    r.add("spring.datasource.password", () -> PASSWORD);
  }

  static void migrate() {
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.CELL_SHARED)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.CELL_SHARED);
      new SchemaMigrator(MigrationOptions.defaults())
          .migrate(
              POSTGRES.getJdbcUrl(),
              "integ",
              new MigrationCredentials.Credentials("mig_integ", PASSWORD));
      String q = "?client=" + enc(CLIENT) + "&service=" + enc(SERVICE);
      String sql =
          connector(TRUC, "TRUC", XROAD.baseUrl() + "/r1" + q, "VAO")
              + connector(
                  BTC,
                  "BTC_TW",
                  XROAD.baseUrl()
                      + "/r1"
                      + q
                      + "&from=000.00.00.H01&fromName="
                      + enc("Tỉnh uỷ Hà Nam")
                      + "&to=000.00.00.G01&toName="
                      + enc("Ban Tổ chức Trung ương"),
                  "RA")
              + "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type,"
              + " external_id, internal_id, valid_from) VALUES (gen_random_uuid(), '%s', '%s',"
                  .formatted(TENANT, TRUC)
              + " 'DON_VI', '000.00.00.H01', '%s', DATE '2026-01-01');".formatted(UNIT);
      admin.createStatement().execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static String connector(UUID id, String code, String endpoint, String direction) {
    return "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
        + " protocol, endpoint, secret_path, direction, enabled) VALUES ('%s', '%s',"
            .formatted(id, TENANT)
        + " gen_random_uuid(), gen_random_uuid(), '%s', 'XROAD', '%s', 'kv/integ/truc', '%s', true);"
            .formatted(code, endpoint.replace("'", "''"), direction);
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    @Primary
    PayloadCipher plainCipher() {
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

  @Value("${local.server.port}")
  int port;

  final HttpClient http = HttpClient.newHttpClient();

  static JdbcTemplate admin() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  String base() {
    return "http://localhost:" + port + "/internal/v1/integ/edxml-transfers";
  }

  HttpResponse<String> submit(UUID transfer, String destination) throws Exception {
    String body =
        ("{\"transferId\":\"%s\",\"packageId\":\"%s\",\"destination\":\"%s\","
                + "\"subject\":\"Hồ sơ cán bộ diện Trung ương quản lý\",\"documentNumber\":\"12/HS-BTC\","
                + "\"attachments\":[{\"name\":\"ho-so.p7m\",\"contentType\":\"application/pkcs7-mime\","
                + "\"contentBase64\":\"%s\"}]}")
            .formatted(
                transfer,
                UUID.randomUUID(),
                destination,
                Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    return http.send(
        HttpRequest.newBuilder(URI.create(base()))
            .header("Content-Type", "application/json")
            .header("X-Tenant-Id", TENANT.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  HttpResponse<String> status(UUID transfer) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(base() + "/" + transfer))
            .header("X-Tenant-Id", TENANT.toString())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  static String doc(UUID transfer) {
    return SVC_PATH + "/edocs/" + transfer;
  }

  @Test
  @Order(1)
  void keoVanBanDenQuaXRoad() {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                admin()
                        .queryForObject(
                            "SELECT count(*) FROM integ.inbox_message WHERE connector_id = ?",
                            Long.class,
                            TRUC)
                    == 2);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                admin()
                        .queryForList(
                            "SELECT error_code FROM integ.dead_letter WHERE connector_id = ?",
                            String.class,
                            TRUC)
                        .contains(TrucTranslator.UNMAPPED_UNIT)
                    && admin()
                        .queryForList(
                            "SELECT status FROM integ.inbox_message WHERE connector_id = ?"
                                + " AND external_msg_id = 'VB-1'",
                            String.class,
                            TRUC)
                        .contains("DA_PHAT"));
    assertThat(
            admin()
                .queryForObject(
                    "SELECT count(*) FROM integ.outbox_event WHERE topic = ?",
                    Long.class,
                    TrucTranslator.TOPIC))
        .isEqualTo(1);
  }

  @Test
  @Order(2)
  void gui503ThuLaiRoiNhanBienNhanVaKhongGuiLai() throws Exception {
    UUID t = UUID.fromString("0192f1a2-0000-7000-8000-00000013f101");
    XROAD.stubFor(
        get(urlPathEqualTo(doc(t) + "/status"))
            .inScenario("t1")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(serviceUnavailable())
            .willSetStateTo("len"));
    XROAD.stubFor(
        get(urlPathEqualTo(doc(t) + "/status"))
            .inScenario("t1")
            .whenScenarioStateIs("len")
            .willReturn(notFound()));
    XROAD.stubFor(
        put(urlPathEqualTo(doc(t)))
            .withHeader("Content-Type", equalTo("application/xml"))
            .willReturn(okJson("{\"status\":\"DA_NHAN\",\"receiptId\":\"BN-1\"}")));

    HttpResponse<String> first = submit(t, "BTC_TW");
    assertThat(first.statusCode()).isEqualTo(202);
    assertThat(first.headers().firstValue("Location"))
        .hasValue("/internal/v1/integ/edxml-transfers/" + t);
    assertThat(first.body()).contains("\"status\":\"CHO\"");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> status(t).body().contains("\"status\":\"DA_XAC_NHAN\""));
    assertThat(status(t).body()).contains("\"ackRef\":\"BN-1\"", "\"acknowledgedAt\":\"");
    HttpResponse<String> again = submit(t, "BTC_TW");
    assertThat(again.statusCode()).isEqualTo(202);
    assertThat(again.body()).contains("DA_XAC_NHAN");
    Thread.sleep(600);
    assertThat(XROAD.findAll(putRequestedFor(urlPathEqualTo(doc(t))))).hasSize(1);
    String sent = XROAD.findAll(putRequestedFor(urlPathEqualTo(doc(t)))).get(0).getBodyAsString();
    EdxmlDocument d = Edxml.parse(sent.getBytes(StandardCharsets.UTF_8), 1 << 20);
    assertThat(d.documentId()).isEqualTo(t.toString());
    assertThat(d.firstRecipient()).isEqualTo("000.00.00.G01");
    assertThat(d.docNumber()).isEqualTo("12/HS-BTC");
    assertThat(d.attachments())
        .singleElement()
        .satisfies(a -> assertThat(a.content()).containsExactly(1, 2, 3));
  }

  @Test
  @Order(3)
  void daCoBienNhanTuTrucThiKhongGuiLan2() throws Exception {
    UUID t = UUID.fromString("0192f1a2-0000-7000-8000-00000013f102");
    XROAD.stubFor(
        get(urlPathEqualTo(doc(t) + "/status"))
            .willReturn(okJson("{\"status\":\"DA_NHAN\",\"receiptId\":\"BN-2\"}")));
    assertThat(submit(t, "BTC_TW").statusCode()).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> status(t).body().contains("\"ackRef\":\"BN-2\""));
    assertThat(XROAD.findAll(putRequestedFor(urlPathEqualTo(doc(t))))).isEmpty();
    assertThat(XROAD.findAll(getRequestedFor(urlPathEqualTo(doc(t) + "/status")))).isNotEmpty();
  }

  @Test
  @Order(4)
  void benNhanTuChoiHetLuotVaoThuChet() throws Exception {
    UUID t = UUID.fromString("0192f1a2-0000-7000-8000-00000013f103");
    XROAD.stubFor(get(urlPathEqualTo(doc(t) + "/status")).willReturn(notFound()));
    XROAD.stubFor(put(urlPathEqualTo(doc(t))).willReturn(okJson("{\"status\":\"TU_CHOI\"}")));
    assertThat(submit(t, "BTC_TW").statusCode()).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> status(t).body().contains("\"status\":\"LOI\""));
    assertThat(status(t).body()).contains("\"lastError\":\"").doesNotContain("\"lastError\":null");
    assertThat(XROAD.findAll(putRequestedFor(urlPathEqualTo(doc(t))))).hasSizeGreaterThan(1);
  }

  @Test
  @Order(5)
  void noiNhanChuaCauHinhVaThieuTenant() throws Exception {
    HttpResponse<String> r = submit(UUID.randomUUID(), "VPTW");
    assertThat(r.statusCode()).isEqualTo(422);
    assertThat(r.body()).contains("DESTINATION_NOT_CONFIGURED");
    assertThat(status(UUID.randomUUID()).statusCode()).isEqualTo(404);
    HttpResponse<String> noTenant =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/" + UUID.randomUUID())).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(noTenant.statusCode()).isEqualTo(400);
    HttpResponse<String> bad =
        http.send(
            HttpRequest.newBuilder(URI.create(base()))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"transferId\":\"%s\",\"destination\":\"BTC_TW\",\"subject\":\"x\",\"attachments\":[{\"name\":\"a\",\"contentBase64\":\"***\"}]}"
                            .formatted(UUID.randomUUID())))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(bad.statusCode()).isEqualTo(400);
  }
}
