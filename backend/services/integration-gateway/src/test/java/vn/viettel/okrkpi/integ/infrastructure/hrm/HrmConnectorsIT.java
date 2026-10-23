package vn.viettel.okrkpi.integ.infrastructure.hrm;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
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
 * DV-13-08 với hệ ngoài giả lập (YC-PT-13): máy chủ SFTP thật trong Testcontainers (tệp CSV đúng,
 * tệp sai cấu trúc, tệp gửi trùng đổi tên, tệp Excel có dòng lỗi), HRM REST và org-service bằng
 * WireMock, và cổng số liệu nguồn của KR (gắn chỉ số, nguồn chậm 10 giây bị cắt dưới 3 giây).
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
      "okrkpi.integ.route.redelivery-delay=PT0.01S",
      "okrkpi.integ.outbound.dispatch.enabled=false",
      "okrkpi.integ.outbound.consumer.enabled=false",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].period=PT0.3S",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].batch.strict-host-key=false",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].batch.min-age=PT0S",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].fields.externalId=Mã cán bộ",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].fields.fullName=Họ và tên",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].fields.nationalId=Số định danh",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].fields.birthDate=Ngày sinh",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].fields.orgUnit=Mã đơn vị",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].fields.jobTitle=Mã chức danh",
      "okrkpi.integ.hrm.connectors[HRM_SFTP].required=externalId,fullName,birthDate",
      "okrkpi.integ.hrm.connectors[HRM].period=PT0.3S",
      "okrkpi.integ.hrm.connectors[HRM].request-timeout=PT15S",
      "okrkpi.integ.hrm.connectors[HRM].rest.next-cursor=/next",
      "okrkpi.integ.hrm.connectors[HRM].indicator.path=/api/indicators/{id}"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HrmConnectorsIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final String SFTP_PASSWORD = "mat-khau-sftp-thu-nghiem";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000013d001");
  static final UUID SFTP_CONN = UUID.fromString("0192f1a2-0000-7000-8000-00000013d002");
  static final UUID REST_CONN = UUID.fromString("0192f1a2-0000-7000-8000-00000013d003");
  static final UUID UNIT = UUID.fromString("0192f1a2-0000-7000-8000-00000013d010");
  static final UUID TITLE = UUID.fromString("0192f1a2-0000-7000-8000-00000013d011");
  static final UUID KR = UUID.fromString("0192f1a2-0000-7000-8000-00000013d020");
  static final UUID KR_SLOW = UUID.fromString("0192f1a2-0000-7000-8000-00000013d021");

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_hrm");

  @SuppressWarnings("resource")
  static final GenericContainer<?> SFTP =
      new GenericContainer<>(
              DockerImageName.parse("mirror.gcr.io/atmoz/sftp:alpine")
                  .asCompatibleSubstituteFor("atmoz/sftp"))
          .withCommand("hrm:" + SFTP_PASSWORD + ":1001::upload")
          .withExposedPorts(22);

  static final WireMockServer HRM = new WireMockServer(wireMockConfig().dynamicPort());
  static final WireMockServer ORG = new WireMockServer(wireMockConfig().dynamicPort());
  static final Path SECRETS;

  static {
    try {
      SECRETS = Files.createTempDirectory("integ-secrets-hrm");
      Files.createDirectories(SECRETS.resolve("hrm-sftp"));
      Files.writeString(SECRETS.resolve("hrm-sftp/password"), SFTP_PASSWORD + "\n");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    POSTGRES.start();
    SFTP.start();
    HRM.start();
    ORG.start();
    ORG.stubFor(
        post(urlPathMatching("/internal/v1/org/sync-runs/[0-9a-f-]+/batches"))
            .willReturn(okJson("{\"records\":1,\"changes\":1,\"errors\":0,\"discrepancies\":0}")));
    HRM.stubFor(
        get(urlPathEqualTo("/api/persons"))
            .withQueryParam("updatedSince", equalTo("1970-01-01T00:00:00Z"))
            .withQueryParam("limit", equalTo("200"))
            .willReturn(
                okJson(
                    "{\"items\":[{\"externalId\":\"NS-R1\",\"fullName\":\"Lê Văn C\","
                        + "\"birthDate\":\"1979-09-02\",\"orgUnit\":\"DV-01\",\"jobTitle\":\"CV\"}],"
                        + "\"next\":\"c2\"}")));
    HRM.stubFor(
        get(urlPathEqualTo("/api/persons"))
            .withQueryParam("updatedSince", equalTo("c2"))
            .willReturn(okJson("{\"items\":[]}")));
    HRM.stubFor(
        get(urlPathEqualTo("/api/indicators/CS-1"))
            .willReturn(okJson("{\"value\":2.6,\"measuredAt\":\"2026-10-22T08:00:00+07:00\"}")));
    HRM.stubFor(
        get(urlPathEqualTo("/api/indicators/CS-CHAM"))
            .willReturn(okJson("{\"value\":1}").withFixedDelay(10_000)));
    migrate();
  }

  @AfterAll
  static void stop() {
    HRM.stop();
    ORG.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=integ");
    r.add("spring.datasource.username", () -> "svc_integ");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add("okrkpi.integ.secrets-dir", SECRETS::toString);
    r.add("okrkpi.integ.hrm.org.base-url", ORG::baseUrl);
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
      String sftp = "sftp://hrm@" + SFTP.getHost() + ":" + SFTP.getMappedPort(22) + "/upload";
      StringBuilder sql = new StringBuilder();
      sql.append(connector(SFTP_CONN, "HRM_SFTP", "SFTP", sftp, "hrm-sftp/password"));
      sql.append(connector(REST_CONN, "HRM", "REST", HRM.baseUrl(), "hrm/token"));
      for (UUID c : List.of(SFTP_CONN, REST_CONN)) {
        sql.append(xref(c, "DON_VI", "DV-01", UNIT)).append(xref(c, "CHUC_DANH", "CV", TITLE));
      }
      admin.createStatement().execute(sql.toString());
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String connector(UUID id, String code, String protocol, String endpoint, String secret) {
    return "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
        + " protocol, endpoint, secret_path, direction, enabled) VALUES ('%s', '%s',"
            .formatted(id, TENANT)
        + " gen_random_uuid(), gen_random_uuid(), '%s', '%s', '%s', '%s', 'VAO', true);"
            .formatted(code, protocol, endpoint, secret);
  }

  static String xref(UUID connector, String type, String external, UUID internal) {
    return "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type, external_id,"
        + " internal_id, valid_from) VALUES (gen_random_uuid(), '%s', '%s', '%s', '%s', '%s',"
            .formatted(TENANT, connector, type, external, internal)
        + " DATE '2026-01-01');";
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

  /** Tải tệp lên SFTP và đặt thời điểm sửa (giây) để thứ tự con trỏ xác định. */
  static void upload(String name, byte[] data, long mtime) throws Exception {
    Session s = new JSch().getSession("hrm", SFTP.getHost(), SFTP.getMappedPort(22));
    s.setPassword(SFTP_PASSWORD);
    s.setConfig("StrictHostKeyChecking", "no");
    s.connect(10_000);
    try {
      ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
      c.connect(10_000);
      c.put(new ByteArrayInputStream(data), "/upload/" + name + ".tmp");
      c.setMtime("/upload/" + name + ".tmp", (int) mtime);
      c.rename("/upload/" + name + ".tmp", "/upload/" + name);
      c.disconnect();
    } finally {
      s.disconnect();
    }
  }

  static List<LoggedRequest> batches(String containing) {
    return ORG.findAll(
        postRequestedFor(urlPathMatching("/internal/v1/org/sync-runs/.+/batches"))
            .withRequestBody(containing(containing)));
  }

  static List<Map<String, Object>> letters(UUID connector) {
    return admin()
        .queryForList(
            "SELECT error_code, error_detail FROM integ.dead_letter WHERE connector_id = ?"
                + " ORDER BY created_at",
            connector);
  }

  static final String HEADER =
      "Mã cán bộ,Họ và tên,Số định danh,Ngày sinh,Mã đơn vị,Mã chức danh\n";

  @Test
  @Order(1)
  void sftpTepDungTepSaiCauTrucVaTepTrung() throws Exception {
    long base = Instant.now().getEpochSecond() - 600;
    byte[] good =
        (HEADER
                + "NS-1,Nguyễn Văn A,001099000001,1980-05-02,DV-01,CV\n"
                + "NS-2,Trần Thị B,,02/03/1985,,\n")
            .getBytes(StandardCharsets.UTF_8);
    upload("a-nhan-su-thang-10.csv", good, base);
    await().atMost(Duration.ofSeconds(30)).until(() -> !batches("NS-1").isEmpty());
    String body = batches("NS-1").get(0).getBodyAsString();
    assertThat(body)
        .contains("\"tenantId\":\"" + TENANT + "\"", "\"connector\":\"HRM_SFTP\"", "\"last\":true")
        .contains("\"orgUnitId\":\"" + UNIT + "\"", "\"jobTitleId\":\"" + TITLE + "\"")
        .contains("\"birthDate\":\"1980-05-02\"", "NS-2");

    upload(
        "b-sai-cau-truc.csv",
        "Mã cán bộ;Họ và tên\nNS-3;C\n".getBytes(StandardCharsets.UTF_8),
        base + 10);
    upload("c-gui-lai.csv", good, base + 20);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                "000000000000|".compareTo(cursor(SFTP_CONN, "hrm-files")) < 0
                    && cursor(SFTP_CONN, "hrm-files").endsWith("c-gui-lai.csv"));
    Thread.sleep(800);
    assertThat(batches("NS-1")).as("tệp gửi lại cùng nội dung không chuyển lần hai").hasSize(1);
    assertThat(letters(SFTP_CONN))
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.get("error_code")).isEqualTo(HrmPersonTranslator.BAD_STRUCTURE);
              assertThat((String) l.get("error_detail")).contains("Ngày sinh");
            });
    assertThat(
            admin()
                .queryForObject(
                    "SELECT count(*) FROM integ.inbox_message WHERE connector_id = ?",
                    Long.class,
                    SFTP_CONN))
        .isEqualTo(2);
  }

  @Test
  @Order(2)
  void sftpTepExcelCoDongLoiVaoThuChetKemSoDong() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Workbook wb = new Workbook(out, "test", "1.0")) {
      Worksheet ws = wb.newWorksheet("Nhân sự");
      String[] h = {"Mã cán bộ", "Họ và tên", "Ngày sinh", "Mã đơn vị", "Mã chức danh"};
      for (int c = 0; c < h.length; c++) {
        ws.value(0, c, h[c]);
      }
      ws.value(1, 0, "NS-5");
      ws.value(1, 1, "Phạm Văn E");
      ws.value(1, 2, "1990-01-01");
      ws.value(2, 0, "NS-6");
      ws.value(2, 1, "Đỗ Thị F");
      ws.value(2, 2, "ngày không rõ");
    }
    upload("d-nhan-su.xlsx", out.toByteArray(), Instant.now().getEpochSecond() - 60);
    await().atMost(Duration.ofSeconds(30)).until(() -> letters(SFTP_CONN).size() == 2);
    Map<String, Object> l = letters(SFTP_CONN).get(1);
    assertThat(l.get("error_code")).isEqualTo(HrmPersonTranslator.BAD_ROWS);
    assertThat((String) l.get("error_detail")).contains("dòng 3 (ngày sinh không hợp lệ)");
    assertThat(batches("NS-5")).as("tệp có dòng lỗi không chuyển nửa tệp").isEmpty();
  }

  @Test
  @Order(3)
  void restHrmKeoTheoConTroVaChuyenOrgService() {
    await().atMost(Duration.ofSeconds(30)).until(() -> !batches("NS-R1").isEmpty());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "c2".equals(cursor(REST_CONN, "hrm-persons")));
    assertThat(batches("NS-R1").get(0).getBodyAsString())
        .contains("\"connector\":\"HRM\"", "\"orgUnitId\":\"" + UNIT + "\"");
  }

  @Test
  @Order(4)
  void soLieuNguonCuaKrVaNguonChamBiCatDuoi3Giay() throws Exception {
    assertThat(put(KR, "CS-1", false).statusCode()).isEqualTo(204);
    assertThat(put(UUID.randomUUID(), "CS-1", false).statusCode())
        .as("mã chỉ số đã gắn KR khác")
        .isEqualTo(409);
    assertThat(put(KR_SLOW, "CS-CHAM", false).statusCode()).isEqualTo(204);

    HttpResponse<String> ok = getValue(KR, "HRM");
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body())
        .contains(
            "\"value\":2.6", "\"fetchedAt\":\"2026-10-22T01:00:00Z\"", "\"dataSource\":\"HRM\"");

    long start = System.nanoTime();
    HttpResponse<String> slow = getValue(KR_SLOW, null);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThat(slow.statusCode()).isEqualTo(502);
    assertThat(slow.body()).contains("TIMEOUT");

    assertThat(getValue(UUID.randomUUID(), null).statusCode()).isEqualTo(404);
    HttpResponse<String> noTenant =
        http.send(
            HttpRequest.newBuilder(URI.create(base() + "/kr-source-values?keyResultId=" + KR))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(noTenant.statusCode()).isEqualTo(400);
  }

  String base() {
    return "http://localhost:" + port + "/internal/v1/integ";
  }

  HttpResponse<String> getValue(UUID kr, String dataSource) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    base()
                        + "/kr-source-values?keyResultId="
                        + kr
                        + (dataSource == null ? "" : "&dataSource=" + dataSource)))
            .header("X-Tenant-Id", TENANT.toString())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  HttpResponse<String> put(UUID kr, String indicator, boolean replace) throws Exception {
    String body =
        "{\"tenantId\":\"%s\",\"connector\":\"HRM\",\"entityType\":\"CHI_SO\",\"externalId\":\"%s\",\"internalId\":\"%s\",\"replace\":%s}"
            .formatted(TENANT, indicator, kr, replace);
    return http.send(
        HttpRequest.newBuilder(URI.create(base() + "/mapping-xref"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static String cursor(UUID connector, String stream) {
    return admin()
        .query(
            "SELECT cursor_value FROM integ.sync_cursor WHERE connector_id = ? AND stream = ?",
            (rs, i) -> rs.getString(1),
            connector,
            stream)
        .stream()
        .findFirst()
        .orElse("000000000000|");
  }
}
