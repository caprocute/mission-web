package vn.viettel.okrkpi.integ.infrastructure.voffice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.domain.EntityType;
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
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Bộ kết nối vOffice với vOffice giả lập WireMock (DV-13-03): ba trang văn bản, một văn bản đổi
 * phiên bản, một lần vOffice lỗi 5xx, một văn bản EdXML, một văn bản gửi tới đơn vị chưa ánh xạ.
 * Kết quả: đúng số EVT-24, không trùng, con trỏ tiến đúng, đơn vị chưa ánh xạ vào thư chết có lý
 * do; mã thông báo lấy theo {@code secret_path}; đổi địa chỉ vOffice chỉ bằng sửa bản ghi kết nối.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.integ.auth-enabled=false",
      "okrkpi.integ.route.redelivery-delay=PT0.01S",
      "okrkpi.integ.outbound.dispatch.enabled=false",
      "okrkpi.integ.outbound.consumer.enabled=false",
      "okrkpi.integ.voffice.period=PT0.2S"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VOfficeConnectorIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final String TOKEN = "ma-thu-nghiem-voffice";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000013c001");
  static final UUID CONNECTOR = UUID.fromString("0192f1a2-0000-7000-8000-00000013c003");
  static final UUID UNIT = UUID.fromString("0192f1a2-0000-7000-8000-00000013c010");
  static final String T1 = "2026-10-20T01:00:00Z";
  static final String T2 = "2026-10-20T02:00:00Z";
  static final String T3 = "2026-10-20T03:00:00Z";
  static final String T4 = "2026-10-20T04:00:00Z";

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_voffice");
  static final WireMockServer VOFFICE = new WireMockServer(wireMockConfig().dynamicPort());
  static final WireMockServer VOFFICE_MOI = new WireMockServer(wireMockConfig().dynamicPort());
  static final Path SECRETS;

  static {
    try {
      SECRETS = Files.createTempDirectory("integ-secrets");
      Files.createDirectories(SECRETS.resolve("voffice"));
      Files.writeString(SECRETS.resolve("voffice/token"), TOKEN + "\n");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    POSTGRES.start();
    VOFFICE.start();
    VOFFICE_MOI.start();
    stubDirectives();
    migrate();
  }

  @AfterAll
  static void stop() {
    VOFFICE.stop();
    VOFFICE_MOI.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=integ");
    r.add("spring.datasource.username", () -> "svc_integ");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add("okrkpi.integ.secrets-dir", SECRETS::toString);
  }

  static String item(String id, int version, String unit, String updatedAt) {
    return """
        {"id":"%s","version":%d,"docNumber":"%s/CT-UBND","issuedDate":"2026-10-19",\
        "issuer":"Uỷ ban nhân dân tỉnh","summary":"Chỉ đạo %s","recipientUnitId":"%s",\
        "updatedAt":"%s","attachments":[{"id":"F-%s","name":"van-ban.pdf"}]}"""
        .formatted(id, version, id, id, unit, updatedAt, id);
  }

  static String edxmlItem(String id, String updatedAt) {
    String xml =
        "<edXML:edXMLEnvelope xmlns:edXML=\\\"http://www.e-doc.vn/Schema/\\\"><edXML:edXMLHeader>"
            + "<edXML:MessageHeader><edXML:From><edXML:OrganId>000.00.00.H01</edXML:OrganId>"
            + "<edXML:OrganName>Văn phòng Tỉnh uỷ</edXML:OrganName></edXML:From>"
            + "<edXML:To><edXML:OrganId>DV-01</edXML:OrganId><edXML:OrganName>Ban</edXML:OrganName>"
            + "</edXML:To><edXML:Code><edXML:CodeNumber>15</edXML:CodeNumber>"
            + "<edXML:CodeNotation>CV-VPTU</edXML:CodeNotation></edXML:Code>"
            + "<edXML:PromulgationInfo><edXML:PromulgationDate>2026-10-18</edXML:PromulgationDate>"
            + "</edXML:PromulgationInfo><edXML:Subject>Triển khai nhiệm vụ quý IV</edXML:Subject>"
            + "</edXML:MessageHeader></edXML:edXMLHeader></edXML:edXMLEnvelope>";
    return """
        {"id":"%s","version":1,"updatedAt":"%s","edxml":"%s"}"""
        .formatted(id, updatedAt, xml);
  }

  static void page(String since, String body) {
    VOFFICE.stubFor(
        get(urlPathEqualTo("/api/v2/directives"))
            .withQueryParam("updatedSince", equalTo(since))
            .withQueryParam("limit", equalTo("200"))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .willReturn(okJson(body)));
  }

  static void stubDirectives() {
    page(
        VOfficeConnector.EPOCH,
        "{\"items\":["
            + item("VB-A", 1, "DV-01", T1)
            + ","
            + item("VB-B", 1, "DV-01", T2)
            + "],\"nextUpdatedSince\":\""
            + T2
            + "\"}");
    // Trang 2: lần đầu vOffice lỗi 5xx, lần sau trả B lặp lại, C và D (đơn vị chưa ánh xạ).
    VOFFICE.stubFor(
        get(urlPathEqualTo("/api/v2/directives"))
            .withQueryParam("updatedSince", equalTo(T2))
            .inScenario("loi-5xx")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("da-hoi-phuc"));
    VOFFICE.stubFor(
        get(urlPathEqualTo("/api/v2/directives"))
            .withQueryParam("updatedSince", equalTo(T2))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .inScenario("loi-5xx")
            .whenScenarioStateIs("da-hoi-phuc")
            .willReturn(
                okJson(
                    "{\"items\":["
                        + item("VB-B", 1, "DV-01", T2)
                        + ","
                        + item("VB-C", 1, "DV-01", T3)
                        + ","
                        + item("VB-D", 1, "DV-99", T3)
                        + "]}")));
    // Trang 3: A đổi phiên bản, E gửi bằng gói EdXML.
    page(
        T3,
        "{\"items\":["
            + item("VB-A", 2, "DV-01", T4)
            + ","
            + edxmlItem("VB-E", T4)
            + "],\"nextUpdatedSince\":\""
            + T4
            + "\"}");
    page(T4, "{\"items\":[]}");
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
      // Đơn vị DV-01 đã ánh xạ trước khi route chạy; DV-99 thì chưa.
      admin
          .createStatement()
          .execute(
              "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
                  + " protocol, endpoint, secret_path, direction, enabled) VALUES ('"
                  + CONNECTOR
                  + "', '"
                  + TENANT
                  + "', gen_random_uuid(), gen_random_uuid(), 'VOFFICE', 'REST', '"
                  + VOFFICE.baseUrl()
                  + "', 'voffice/token', 'HAI_CHIEU', true);"
                  + " INSERT INTO integ.mapping_xref (id, tenant_id, connector_id,"
                  + " entity_type, external_id, internal_id, valid_from) VALUES"
                  + " (gen_random_uuid(), '"
                  + TENANT
                  + "', '"
                  + CONNECTOR
                  + "', 'DON_VI', 'DV-01', '"
                  + UNIT
                  + "', DATE '2026-01-01')");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
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

  @Autowired MappingXrefService mappings;
  @Autowired TransactionTemplate tx;

  @Value("${local.server.port}")
  int port;

  static JdbcTemplate admin() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @Test
  @Order(1)
  void keoBaTrangPhatDungSoSuKienConTroTienDungVaDonViChuaAnhXaVaoThuChet() throws Exception {
    JdbcTemplate db = admin();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                T4.equals(
                    db
                        .query(
                            "SELECT cursor_value FROM integ.sync_cursor WHERE connector_id = ?"
                                + " AND stream = 'directives'",
                            (rs, i) -> rs.getString(1),
                            CONNECTOR)
                        .stream()
                        .findFirst()
                        .orElse(null)));
    Thread.sleep(1000); // thêm vài lượt kéo trang rỗng và trang lặp

    List<Map<String, Object>> events =
        db.queryForList(
            "SELECT aggregate_id, aggregate_version, event_key FROM integ.outbox_event"
                + " WHERE topic = 'integ.document.received.v1' ORDER BY created_at, id");
    assertThat(events).as("A1, B1, C1, A2, E1; không trùng B, không phát D").hasSize(5);
    for (Map<String, Object> e : events) {
      assertThat(e.get("event_key")).isEqualTo(TENANT + ":" + e.get("aggregate_id"));
    }
    UUID docA = docId("VB-A");
    assertThat(
            events.stream()
                .filter(e -> docA.equals(e.get("aggregate_id")))
                .map(e -> ((Number) e.get("aggregate_version")).intValue())
                .toList())
        .as("văn bản đổi phiên bản giữ mã nội bộ, phiên bản tăng")
        .containsExactly(1, 2);
    assertThat(docId("VB-E")).isNotNull();

    List<Map<String, Object>> letters =
        db.queryForList(
            "SELECT error_code, error_detail FROM integ.dead_letter WHERE connector_id = ?",
            CONNECTOR);
    assertThat(letters).hasSize(1);
    assertThat(letters.get(0).get("error_code")).isEqualTo(VOfficeTranslator.UNMAPPED_UNIT);
    assertThat((String) letters.get(0).get("error_detail")).contains("DV-99");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM integ.inbox_message WHERE status = 'LOI'", Long.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM integ.inbox_message WHERE external_msg_id = 'VB-B:1'",
                Long.class))
        .isEqualTo(1);
    VOFFICE.verify(
        moreThanOrExactly(2),
        getRequestedFor(urlPathEqualTo("/api/v2/directives"))
            .withQueryParam("updatedSince", equalTo(T2)));
  }

  @Test
  @Order(2)
  void traCuuVanBanDaBanHanhChiDoiDiaChiBangCauHinh() throws Exception {
    VOFFICE_MOI.stubFor(
        get(urlPathEqualTo("/api/v2/documents"))
            .withQueryParam("unitId", equalTo("DV-01"))
            .withQueryParam("q", equalTo("kiểm tra"))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .willReturn(
                okJson(
                    "{\"items\":[{\"id\":\"47-BC\",\"docNumber\":\"47-BC/BTCĐUK\","
                        + "\"issuedDate\":\"2026-10-19\",\"summary\":\"Báo cáo chuyên đề\","
                        + "\"signerName\":\"Trần Thị Bình\",\"signatureValid\":true}],"
                        + "\"nextCursor\":\"c2\"}")));
    admin()
        .update(
            "UPDATE integ.connector SET endpoint = ? WHERE id = ?",
            VOFFICE_MOI.baseUrl(),
            CONNECTOR);

    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> res =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/internal/v1/integ/issued-docs?tenantId="
                            + TENANT
                            + "&orgUnitId="
                            + UNIT
                            + "&q=ki%E1%BB%83m%20tra&limit=20"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body())
        .contains("\"ref\":\"47-BC\"", "\"docDate\":\"2026-10-19\"", "\"signatureValid\":true")
        .contains("\"nextCursor\":\"c2\"");

    HttpResponse<String> unmapped =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/internal/v1/integ/issued-docs?tenantId="
                            + TENANT
                            + "&orgUnitId="
                            + UUID.randomUUID()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(unmapped.statusCode()).isEqualTo(200);
    assertThat(unmapped.body()).contains("\"items\":[]");

    HttpResponse<String> bad =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/internal/v1/integ/issued-docs?tenantId="
                            + TENANT
                            + "&orgUnitId="
                            + UNIT
                            + "&limit=500"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(bad.statusCode()).isEqualTo(400);
  }

  UUID docId(String externalId) {
    TenantId tenant = TenantId.of(TENANT);
    Object[] out = new Object[1];
    TenantContext.run(
        tenant,
        () ->
            out[0] =
                tx.execute(
                    s ->
                        mappings
                            .resolve(
                                tenant, CONNECTOR, EntityType.VAN_BAN, externalId, LocalDate.now())
                            .orElse(null)));
    return (UUID) out[0];
  }
}
