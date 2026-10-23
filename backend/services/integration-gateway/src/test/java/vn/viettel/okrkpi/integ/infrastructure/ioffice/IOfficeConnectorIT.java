package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okXml;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import vn.viettel.okrkpi.integ.infrastructure.voffice.DirectiveEvents;
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
 * Bộ kết nối iOffice với hai tenant dùng cùng mã kết nối nhưng khác giao thức (DV-13-04): tenant A
 * nối iOffice giả lập qua REST, tenant B qua SOAP, chỉ khác bản ghi {@code integ.connector}. Kết
 * quả: đúng số EVT-24 cho mỗi tenant, văn bản lặp không phát lại, văn bản đổi phiên bản phát lại,
 * đơn vị chưa ánh xạ và văn bản mật vào thư chết có lý do, con trỏ tiến đúng.
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
      "okrkpi.integ.voffice.enabled=false",
      "okrkpi.integ.ioffice.period=PT0.2S"
    })
class IOfficeConnectorIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final String TOKEN = "ma-thu-nghiem-ioffice";
  static final UUID TENANT_A = UUID.fromString("0192f1a2-0000-7000-8000-00000013d001");
  static final UUID TENANT_B = UUID.fromString("0192f1a2-0000-7000-8000-00000013d002");
  static final UUID CONN_A = UUID.fromString("0192f1a2-0000-7000-8000-00000013d011");
  static final UUID CONN_B = UUID.fromString("0192f1a2-0000-7000-8000-00000013d012");
  static final String EPOCH = IOfficeConnector.EPOCH;
  static final String T1 = "2026-10-20T01:00:00Z";
  static final String T2 = "2026-10-20T02:00:00Z";

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_ioffice");
  static final WireMockServer IOFFICE = new WireMockServer(wireMockConfig().dynamicPort());
  static final Path SECRETS;

  static {
    try {
      SECRETS = Files.createTempDirectory("integ-secrets");
      Files.createDirectories(SECRETS.resolve("ioffice"));
      Files.writeString(SECRETS.resolve("ioffice/token"), TOKEN + "\n");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    POSTGRES.start();
    IOFFICE.start();
    stubRest();
    stubSoap();
    migrate();
  }

  @AfterAll
  static void stop() {
    IOFFICE.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=integ");
    r.add("spring.datasource.username", () -> "svc_integ");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add("okrkpi.integ.secrets-dir", SECRETS::toString);
  }

  static String item(String id, int version, String unit, String secrecy, String updatedAt) {
    return """
        {"vanBanId":"%s","phienBan":%d,"soKyHieu":"%s/CV-UBND","ngayBanHanh":"19/10/2026",\
        "coQuanBanHanh":"Uỷ ban nhân dân tỉnh","trichYeu":"Văn bản %s","doMat":"%s",\
        "noiNhan":[{"maDonVi":"%s","tenDonVi":"Đơn vị"}],"thoiDiemCapNhat":"%s"}"""
        .formatted(id, version, id, id, secrecy, unit, updatedAt);
  }

  static void rest(String since, String body) {
    IOFFICE.stubFor(
        get(urlPathEqualTo(IOfficeClient.REST_PATH))
            .withQueryParam("tuThoiDiem", equalTo(since))
            .withQueryParam("soLuong", equalTo("200"))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .willReturn(okJson(body)));
  }

  static void stubRest() {
    rest(
        EPOCH,
        "{\"duLieu\":["
            + item("IO-A", 1, "DV-01", "THUONG", "2026-10-20T07:00:00+07:00")
            + ","
            + item("IO-B", 1, "DV-99", "THUONG", "2026-10-20T07:30:00+07:00")
            + ","
            + item("IO-C", 1, "DV-01", "MAT", T1)
            + "],\"thoiDiemTiepTheo\":\""
            + T1
            + "\"}");
    // Trang 2: A lặp lại cùng phiên bản và A đổi sang phiên bản 2.
    rest(
        T1,
        "{\"duLieu\":["
            + item("IO-A", 1, "DV-01", "THUONG", T1)
            + ","
            + item("IO-A", 2, "DV-01", "THUONG", "2026-10-20T09:00:00+07:00")
            + "]}");
    rest(T2, "{\"duLieu\":[]}");
  }

  static void soap(String since, String items) {
    IOFFICE.stubFor(
        post(urlPathEqualTo(IOfficeClient.SOAP_PATH))
            .withHeader("SOAPAction", equalTo("\"" + IOfficeClient.SOAP_ACTION + "\""))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .withRequestBody(containing("<vb:tuThoiDiem>" + since + "</vb:tuThoiDiem>"))
            .willReturn(
                okXml(
                    "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                        + " xmlns:vb=\"urn:ioffice:vanban:v1\"><soapenv:Body>"
                        + "<vb:layDanhSachVanBanDenResponse>"
                        + items
                        + "</vb:layDanhSachVanBanDenResponse></soapenv:Body></soapenv:Envelope>")));
  }

  static void stubSoap() {
    soap(
        EPOCH,
        "<vb:vanBan><vb:vanBanId>IO-S</vb:vanBanId><vb:phienBan>1</vb:phienBan>"
            + "<vb:soKyHieu>08/KH-UBND</vb:soKyHieu><vb:ngayBanHanh>2026-10-18</vb:ngayBanHanh>"
            + "<vb:coQuanBanHanh>Uỷ ban nhân dân huyện</vb:coQuanBanHanh>"
            + "<vb:trichYeu>Kế hoạch kiểm tra</vb:trichYeu><vb:noiNhan><vb:donVi>"
            + "<vb:maDonVi>DV-77</vb:maDonVi></vb:donVi><vb:donVi><vb:maDonVi>DV-01</vb:maDonVi>"
            + "</vb:donVi></vb:noiNhan><vb:thoiDiemCapNhat>"
            + T1
            + "</vb:thoiDiemCapNhat></vb:vanBan><vb:thoiDiemTiepTheo>"
            + T2
            + "</vb:thoiDiemTiepTheo>");
    soap(T2, "");
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
      StringBuilder sql = new StringBuilder();
      for (Object[] t : new Object[][] {{TENANT_A, CONN_A, "REST"}, {TENANT_B, CONN_B, "SOAP"}}) {
        sql.append(
                "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by,"
                    + " connector_code, protocol, endpoint, secret_path, direction, enabled)"
                    + " VALUES ('")
            .append(t[1])
            .append("', '")
            .append(t[0])
            .append("', gen_random_uuid(), gen_random_uuid(), 'IOFFICE', '")
            .append(t[2])
            .append("', '")
            .append(IOFFICE.baseUrl())
            .append("', 'ioffice/token', 'VAO', true);")
            .append(
                " INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type,"
                    + " external_id, internal_id, valid_from) VALUES (gen_random_uuid(), '")
            .append(t[0])
            .append("', '")
            .append(t[1])
            .append("', 'DON_VI', 'DV-01', gen_random_uuid(), DATE '2026-01-01');");
      }
      admin.createStatement().execute(sql.toString());
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

  static JdbcTemplate admin() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  static String cursor(JdbcTemplate db, UUID connector) {
    return db
        .query(
            "SELECT cursor_value FROM integ.sync_cursor WHERE connector_id = ? AND stream = ?",
            (rs, i) -> rs.getString(1),
            connector,
            IOfficeConnector.STREAM)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Test
  void haiTenantRestVaSoapCungMaKetNoiPhatDungEvt24VaThuChetCoLyDo() throws Exception {
    JdbcTemplate db = admin();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> T2.equals(cursor(db, CONN_A)) && T2.equals(cursor(db, CONN_B)));
    Thread.sleep(1000); // thêm vài lượt kéo trang rỗng

    List<Map<String, Object>> a =
        db.queryForList(
            "SELECT aggregate_id, aggregate_version, event_key FROM integ.outbox_event"
                + " WHERE topic = ? AND tenant_id = ? ORDER BY created_at, id",
            DirectiveEvents.TOPIC,
            TENANT_A);
    assertThat(a)
        .as("A1 và A2; không trùng A1, không phát B (chưa ánh xạ) và C (văn bản mật)")
        .hasSize(2);
    assertThat(a.get(0).get("aggregate_id")).isEqualTo(a.get(1).get("aggregate_id"));
    assertThat(a.stream().map(e -> ((Number) e.get("aggregate_version")).intValue()).toList())
        .containsExactly(1, 2);
    assertThat(a.get(0).get("event_key")).isEqualTo(TENANT_A + ":" + a.get(0).get("aggregate_id"));

    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM integ.outbox_event WHERE topic = ? AND tenant_id = ?",
                Long.class,
                DirectiveEvents.TOPIC,
                TENANT_B))
        .as("văn bản SOAP gửi hai nơi nhận chỉ phát một lần cho nơi nhận đã ánh xạ")
        .isEqualTo(1);

    List<String> letters =
        db.queryForList(
            "SELECT error_code FROM integ.dead_letter WHERE connector_id = ? ORDER BY error_code",
            String.class,
            CONN_A);
    assertThat(letters)
        .containsExactly(DirectiveEvents.UNMAPPED_UNIT, IOfficeTranslator.SECRET_DOCUMENT);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM integ.dead_letter WHERE connector_id = ?",
                Long.class,
                CONN_B))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM integ.inbox_message WHERE tenant_id = ?"
                    + " AND external_msg_id = 'IO-A:1'",
                Long.class,
                TENANT_A))
        .isEqualTo(1);
  }
}
