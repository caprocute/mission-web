package vn.viettel.okrkpi.integ.infrastructure.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * DV-13-07 với CSDL đảng viên và org-service giả lập (YC-PT-13): đảng viên mới, chuyển sinh hoạt
 * sang chi bộ khác, ra khỏi Đảng thành đúng bản ghi cho org-service (nhánh Đảng); kéo lại từ đầu
 * không gửi trùng; số định danh không ở dạng rõ trong {@code integ} và nhật ký; số liệu nguồn cho
 * KR qua cùng use case DV-13-08, nguồn lỗi thì 502 để giao diện cho nhập tay.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.integ.auth-enabled=false",
      "okrkpi.integ.voffice.enabled=false",
      "okrkpi.integ.truc.enabled=false",
      "okrkpi.integ.route.redelivery-delay=PT0.01S",
      "okrkpi.integ.outbound.dispatch.enabled=false",
      "okrkpi.integ.outbound.consumer.enabled=false",
      "okrkpi.integ.sync-run.period=PT0.2S",
      "okrkpi.integ.sync-run.fetch-attempts=1",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].trigger=RUN",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].party.outbound=false",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.path=/api/dang-vien/v1/dang-vien",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.cursor-param=capNhatTu",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.limit-param=gioiHan",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.page-size=3",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.initial-cursor=0",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.items=/duLieu",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].rest.next-cursor=/conTroTiep",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.externalId=/maDangVien",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.fullName=/hoTen",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.nationalId=/soDinhDanh",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.birthDate=/ngaySinh",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.partyMember=/tinhTrangDangTich",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.orgUnit=/toChucDang/ma",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.jobTitle=/chucVuDang/ma",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.head=/chucVuDang/laBiThu",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].fields.effectiveFrom=/ngayHieuLuc",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].values.partyMember[Đang\\ sinh\\ hoạt]=true",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].values.partyMember[Ra\\ khỏi\\ Đảng]=false",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].indicator.path=/api/dang-vien/v1/chi-so/{id}",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].indicator.value=/giaTri",
      "okrkpi.integ.hrm.connectors[DANG_VIEN].indicator.measured-at=/thoiDiem"
    })
// Dùng lại bộ mã hoá AES-GCM thật và serializer giả của DV-13-06.
@Import(CbccvcSyncIT.Fakes.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DangVienSyncIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000013d001");
  static final UUID DANG_VIEN = UUID.fromString("0192f1a2-0000-7000-8000-00000013d002");
  static final UUID CHI_BO_1 = UUID.fromString("0192f1a2-0000-7000-8000-00000013d010");
  static final UUID CHI_BO_2 = UUID.fromString("0192f1a2-0000-7000-8000-00000013d011");
  static final UUID BI_THU = UUID.fromString("0192f1a2-0000-7000-8000-00000013d012");
  static final UUID DANG_VIEN_CV = UUID.fromString("0192f1a2-0000-7000-8000-00000013d013");
  static final UUID KR = UUID.fromString("0192f1a2-0000-7000-8000-00000013d020");

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_dangvien");
  static final ObjectMapper JSON = new ObjectMapper();

  /** Nhật ký thay đổi ở nguồn: vị trí trong danh sách là con trỏ. */
  static final List<String> CHANGES = new CopyOnWriteArrayList<>();

  static final List<JsonNode> RECORDS = new CopyOnWriteArrayList<>();
  static final List<String> RUNS = new CopyOnWriteArrayList<>();
  static final AtomicBoolean INDICATOR_DOWN = new AtomicBoolean();
  static final HttpServer SOURCE;
  static final HttpServer ORG;

  static {
    try {
      POSTGRES.start();
      SOURCE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      SOURCE.createContext("/api/dang-vien/v1/dang-vien", DangVienSyncIT::members);
      SOURCE.createContext("/api/dang-vien/v1/chi-so/", DangVienSyncIT::indicator);
      SOURCE.start();
      ORG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ORG.createContext("/internal/v1/org/sync-runs/", DangVienSyncIT::org);
      ORG.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    migrate();
  }

  static String nationalId(int i) {
    return "%012d".formatted(1_077_000_000L + i);
  }

  static String member(
      int i, String name, String status, String chiBo, String chucVu, boolean biThu, String from) {
    return ("{\"maDangVien\":\"DV-%03d\",\"hoTen\":\"%s\",\"soDinhDanh\":\"%s\","
            + "\"ngaySinh\":\"1975-05-19\",\"tinhTrangDangTich\":\"%s\",%s\"ngayHieuLuc\":\"%s\"}")
        .formatted(
            i,
            name,
            nationalId(i),
            status,
            chiBo == null
                ? ""
                : "\"toChucDang\":{\"ma\":\"%s\"},\"chucVuDang\":{\"ma\":\"%s\",\"laBiThu\":%s},"
                    .formatted(chiBo, chucVu, biThu),
            from);
  }

  static void members(HttpExchange ex) throws IOException {
    Map<String, String> q = CbccvcSyncIT.query(ex.getRequestURI().getRawQuery());
    int from = Integer.parseInt(q.getOrDefault("capNhatTu", "0"));
    int to = Math.min(CHANGES.size(), from + Integer.parseInt(q.getOrDefault("gioiHan", "3")));
    String items = from >= to ? "" : String.join(",", CHANGES.subList(from, to));
    CbccvcSyncIT.reply(
        ex, 200, "{\"duLieu\":[" + items + "],\"conTroTiep\":\"" + Math.max(from, to) + "\"}");
  }

  static void indicator(HttpExchange ex) throws IOException {
    if (INDICATOR_DOWN.get() || !ex.getRequestURI().getPath().endsWith("/TY_LE_KET_NAP")) {
      CbccvcSyncIT.reply(ex, 503, "{}");
      return;
    }
    CbccvcSyncIT.reply(ex, 200, "{\"giaTri\":4.5,\"thoiDiem\":\"2026-10-20T08:00:00+07:00\"}");
  }

  static void org(HttpExchange ex) throws IOException {
    JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
    RUNS.add(ex.getRequestURI().getPath().split("/")[5] + "|" + body.path("connector").asText());
    body.path("records").forEach(RECORDS::add);
    CbccvcSyncIT.reply(ex, 200, "{\"records\":0}");
  }

  @AfterAll
  static void stop() {
    SOURCE.stop(0);
    ORG.stop(0);
  }

  static String base(HttpServer s) {
    return "http://127.0.0.1:" + s.getAddress().getPort();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=integ");
    r.add("spring.datasource.username", () -> "svc_integ");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add("okrkpi.integ.hrm.org.base-url", () -> base(ORG));
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
      String sql =
          "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
              + " protocol, endpoint, secret_path, direction, enabled) VALUES ('%s', '%s',"
                  .formatted(DANG_VIEN, TENANT)
              + " gen_random_uuid(), gen_random_uuid(), 'DANG_VIEN', 'REST', '%s',"
                  .formatted(base(SOURCE))
              + " 'kv/integ/dang-vien', 'VAO', true);"
              + xref("DON_VI", "CB-01", CHI_BO_1)
              + xref("DON_VI", "CB-02", CHI_BO_2)
              + xref("CHUC_DANH", "BT", BI_THU)
              + xref("CHUC_DANH", "DV", DANG_VIEN_CV);
      admin.createStatement().execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String xref(String type, String external, UUID internal) {
    return "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type, external_id,"
        + " internal_id, valid_from) VALUES (gen_random_uuid(), '%s', '%s', '%s', '%s', '%s',"
            .formatted(TENANT, DANG_VIEN, type, external, internal)
        + " DATE '2026-01-01');";
  }

  @Value("${local.server.port}")
  int port;

  final HttpClient http = HttpClient.newHttpClient();

  static JdbcTemplate admin() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  String gateway() {
    return "http://localhost:" + port + "/internal/v1/integ";
  }

  JsonNode syncNow() throws Exception {
    UUID run = UUID.randomUUID();
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(gateway() + "/sync-runs"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"tenantId\":\"%s\",\"runId\":\"%s\",\"connector\":\"DANG_VIEN\"}"
                            .formatted(TENANT, run)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(r.statusCode()).isEqualTo(202);
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> List.of("XONG", "LOI").contains(run(run).path("status").asText()));
    return run(run);
  }

  JsonNode run(UUID id) throws Exception {
    return JSON.readTree(
        http.send(
                HttpRequest.newBuilder(
                        URI.create(gateway() + "/sync-runs/" + id + "?tenantId=" + TENANT))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .body());
  }

  static JsonNode record(String externalId) {
    return RECORDS.stream()
        .filter(r -> externalId.equals(r.path("externalId").asText()))
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  @Test
  @Order(1)
  void dangVienMoiVaoChiBoTheoCayToChucDang() throws Exception {
    CHANGES.add(member(1, "Nguyễn Văn An", "Đang sinh hoạt", "CB-01", "BT", true, "2020-03-01"));
    CHANGES.add(member(2, "Trần Thị Bình", "Đang sinh hoạt", "CB-01", "DV", false, "2021-06-01"));
    CHANGES.add(member(3, "Lê Văn Cường", "Đang sinh hoạt", "CB-02", "DV", false, "2019-01-01"));
    JsonNode run = syncNow();
    assertThat(run.path("status").asText()).isEqualTo("XONG");
    assertThat(run.path("deadLetters").asInt()).isZero();
    assertThat(RECORDS).hasSize(3);
    assertThat(RUNS).allMatch(r -> r.endsWith("|DANG_VIEN"));
    JsonNode an = record("DV-001");
    assertThat(an.path("partyMember").asBoolean()).isTrue();
    assertThat(an.path("employmentStatus").isNull()).as("nguồn Đảng không ghi công tác").isTrue();
    assertThat(an.path("posts").get(0).path("orgUnitId").asText()).isEqualTo(CHI_BO_1.toString());
    assertThat(an.path("posts").get(0).path("jobTitleId").asText()).isEqualTo(BI_THU.toString());
    assertThat(an.path("posts").get(0).path("head").asBoolean()).isTrue();
  }

  @Test
  @Order(2)
  void chuyenSinhHoatRaKhoiDangVaKetNapMoi() throws Exception {
    RECORDS.clear();
    CHANGES.add(member(2, "Trần Thị Bình", "Đang sinh hoạt", "CB-02", "DV", false, "2026-11-01"));
    CHANGES.add(member(3, "Lê Văn Cường", "Ra khỏi Đảng", null, null, false, "2026-10-15"));
    CHANGES.add(member(4, "Phạm Thu Dung", "Đang sinh hoạt", "CB-01", "DV", false, "2026-10-19"));
    assertThat(syncNow().path("status").asText()).isEqualTo("XONG");
    assertThat(RECORDS)
        .extracting(r -> r.path("externalId").asText())
        .containsExactly("DV-002", "DV-003", "DV-004");
    JsonNode binh = record("DV-002");
    assertThat(binh.path("posts").get(0).path("orgUnitId").asText()).isEqualTo(CHI_BO_2.toString());
    assertThat(binh.path("posts").get(0).path("validFrom").asText()).isEqualTo("2026-11-01");
    JsonNode cuong = record("DV-003");
    assertThat(cuong.path("partyMember").asBoolean()).isFalse();
    assertThat(cuong.path("posts")).isEmpty();
    assertThat(cuong.path("effectiveFrom").asText()).isEqualTo("2026-10-15");
    assertThat(record("DV-004").path("partyMember").asBoolean()).isTrue();
  }

  @Test
  @Order(3)
  void chayLaiVaKeoLaiTuDauKhongGuiTrung() throws Exception {
    RECORDS.clear();
    assertThat(syncNow().path("status").asText()).isEqualTo("XONG");
    assertThat(RECORDS).isEmpty();
    // Đặt lại con trỏ: các trang kéo lại y nguyên bị luồng vào coi là trùng, không gửi lại.
    admin()
        .update(
            "UPDATE integ.sync_cursor SET cursor_value = '0' WHERE connector_id = ?", DANG_VIEN);
    assertThat(syncNow().path("status").asText()).isEqualTo("XONG");
    assertThat(RECORDS).isEmpty();
    assertThat(
            admin()
                .queryForObject(
                    "SELECT count(*) FROM integ.inbox_message WHERE connector_id = ?",
                    Long.class,
                    DANG_VIEN))
        .isEqualTo(2L);
  }

  @Test
  @Order(4)
  void soDinhDanhKhongODangRo(CapturedOutput output) {
    byte[] sample = nationalId(3).getBytes(StandardCharsets.UTF_8);
    List<byte[]> raws =
        admin()
            .query(
                "SELECT raw_enc FROM integ.inbox_message WHERE connector_id = ?",
                (rs, i) -> rs.getBytes(1),
                DANG_VIEN);
    assertThat(raws).isNotEmpty().allMatch(raw -> CbccvcSyncIT.indexOf(raw, sample) < 0);
    assertThat(
            admin()
                .queryForObject(
                    "SELECT count(*) FROM integ.dead_letter WHERE error_detail LIKE '%1077%'",
                    Long.class))
        .isZero();
    assertThat(output.getAll()).doesNotContain(nationalId(1), nationalId(3));
  }

  @Test
  @Order(5)
  void soLieuNguonChoKrVaNguonLoiChoNhapTay() throws Exception {
    HttpResponse<String> put =
        http.send(
            HttpRequest.newBuilder(URI.create(gateway() + "/mapping-xref"))
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"tenantId\":\"%s\",\"connector\":\"DANG_VIEN\",\"entityType\":\"CHI_SO\",\"externalId\":\"TY_LE_KET_NAP\",\"internalId\":\"%s\"}"
                            .formatted(TENANT, KR)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(put.statusCode()).isEqualTo(204);
    HttpResponse<String> ok = krValue();
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body()).contains("\"value\":4.5", "\"fetchedAt\":\"2026-10-20T01:00:00Z\"");
    INDICATOR_DOWN.set(true);
    assertThat(krValue().statusCode()).as("nguồn lỗi thì cho nhập tay").isEqualTo(502);
  }

  HttpResponse<String> krValue() throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(gateway() + "/kr-source-values?keyResultId=" + KR))
            .header("X-Tenant-Id", TENANT.toString())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
