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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 * DV-13-06 với NDXP, LGSP và org-service giả lập (YC-PT-13): 100.000 hồ sơ CBCCVC qua 500 trang
 * trong lần "Đồng bộ ngay", NDXP gián đoạn ở trang 251 thì lần chạy lỗi, lần sau chạy tiếp từ con
 * trỏ không trùng, không sót; số định danh không ở dạng rõ trong {@code inbox_message}, sự kiện
 * outbox hay nhật ký; chỉ tiêu KT-XH chưa công bố không mang cờ {@code da_cong_bo} và không được
 * tính cho KR.
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
      "okrkpi.integ.sync-run.fetch-attempts=2",
      "okrkpi.integ.sync-run.fetch-backoff=PT0.05S",
      "okrkpi.integ.lgsp.period=PT0.3S",
      "okrkpi.integ.hrm.connectors[CBCCVC].trigger=RUN",
      "okrkpi.integ.hrm.connectors[CBCCVC].request-timeout=PT30S",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.path=/api/cbccvc/v1/ho-so",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.cursor-param=capNhatTu",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.limit-param=gioiHan",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.page-size=200",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.initial-cursor=0",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.items=/duLieu",
      "okrkpi.integ.hrm.connectors[CBCCVC].rest.next-cursor=/conTroTiep",
      "okrkpi.integ.hrm.connectors[CBCCVC].fields.externalId=/maHoSo",
      "okrkpi.integ.hrm.connectors[CBCCVC].fields.fullName=/hoTen",
      "okrkpi.integ.hrm.connectors[CBCCVC].fields.nationalId=/soDinhDanh",
      "okrkpi.integ.hrm.connectors[CBCCVC].fields.birthDate=/ngaySinh",
      "okrkpi.integ.hrm.connectors[CBCCVC].fields.orgUnit=/coQuan/maDinhDanh",
      "okrkpi.integ.hrm.connectors[CBCCVC].fields.jobTitle=/chucVu/ma"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CbccvcSyncIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000013c001");
  static final UUID CBCCVC = UUID.fromString("0192f1a2-0000-7000-8000-00000013c002");
  static final UUID LGSP = UUID.fromString("0192f1a2-0000-7000-8000-00000013c003");
  static final UUID UNIT = UUID.fromString("0192f1a2-0000-7000-8000-00000013c010");
  static final UUID TITLE = UUID.fromString("0192f1a2-0000-7000-8000-00000013c011");
  static final UUID KR_GRDP = UUID.fromString("0192f1a2-0000-7000-8000-00000013c020");
  static final UUID KR_CPI = UUID.fromString("0192f1a2-0000-7000-8000-00000013c021");
  static final UUID RUN_1 = UUID.fromString("0192f1a2-0000-7000-8000-00000013c101");
  static final UUID RUN_2 = UUID.fromString("0192f1a2-0000-7000-8000-00000013c102");
  static final UUID RUN_RETRY = UUID.fromString("0192f1a2-0000-7000-8000-00000013c103");
  static final int TOTAL = 100_000;
  static final int PAGE = 200;
  static final int FAIL_AT = 50_000;

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_integ_cbccvc");
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicBoolean NDXP_DOWN = new AtomicBoolean(true);
  static final AtomicInteger NDXP_PAGES = new AtomicInteger();
  static final Map<String, Integer> RECEIVED = new ConcurrentHashMap<>();
  static final AtomicInteger RECORDS = new AtomicInteger();
  static final List<JsonNode> CLOSED = new CopyOnWriteArrayList<>();
  static final Set<String> RUNS_SEEN = ConcurrentHashMap.newKeySet();
  static final HttpServer NDXP;
  static final HttpServer ORG;
  static final HttpServer LGSP_SERVER;

  static {
    try {
      POSTGRES.start();
      NDXP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      NDXP.setExecutor(Executors.newFixedThreadPool(4));
      NDXP.createContext("/api/cbccvc/v1/ho-so", CbccvcSyncIT::ndxp);
      NDXP.start();
      ORG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      ORG.setExecutor(Executors.newFixedThreadPool(4));
      ORG.createContext("/internal/v1/org/sync-runs/", CbccvcSyncIT::org);
      ORG.start();
      LGSP_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      LGSP_SERVER.createContext("/api/chi-tieu-ktxh", CbccvcSyncIT::lgsp);
      LGSP_SERVER.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    migrate();
  }

  static String nationalId(int i) {
    return "%012d".formatted(1_099_000_000L + i);
  }

  /** NDXP giả: con trỏ là vị trí bản ghi; gián đoạn ở vị trí {@link #FAIL_AT} khi bật cờ lỗi. */
  static void ndxp(HttpExchange ex) throws IOException {
    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
    int from = Integer.parseInt(q.getOrDefault("capNhatTu", "0"));
    int limit = Integer.parseInt(q.getOrDefault("gioiHan", "200"));
    if (from >= FAIL_AT && NDXP_DOWN.get()) {
      reply(ex, 503, "{}");
      return;
    }
    NDXP_PAGES.incrementAndGet();
    StringBuilder b = new StringBuilder("{\"duLieu\":[");
    int to = Math.min(TOTAL, from + limit);
    for (int i = from; i < to; i++) {
      if (i > from) {
        b.append(',');
      }
      b.append(
          "{\"maHoSo\":\"HS-%06d\",\"hoTen\":\"Cán bộ %d\",\"soDinhDanh\":\"%s\",\"ngaySinh\":\"1980-01-01\",\"coQuan\":{\"maDinhDanh\":\"CQ-01\"},\"chucVu\":{\"ma\":\"CV\"}}"
              .formatted(i, i, nationalId(i)));
    }
    b.append("],\"conTroTiep\":\"").append(to).append("\"}");
    reply(ex, 200, b.toString());
  }

  /** org-service giả: đếm bản ghi theo mã hồ sơ, ghi lại lô đóng lần chạy. */
  static void org(HttpExchange ex) throws IOException {
    JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
    String path = ex.getRequestURI().getPath();
    RUNS_SEEN.add(path.split("/")[5]);
    for (JsonNode r : body.path("records")) {
      RECEIVED.merge(r.path("externalId").asText(), 1, Integer::sum);
      RECORDS.incrementAndGet();
    }
    if (body.path("last").asBoolean() && body.path("records").isEmpty()) {
      CLOSED.add(
          JSON.createObjectNode()
              .put("run", path.split("/")[5])
              .setAll((com.fasterxml.jackson.databind.node.ObjectNode) body));
    }
    reply(ex, 200, "{\"records\":0}");
  }

  static void lgsp(HttpExchange ex) throws IOException {
    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
    if (!"1970-01-01T00:00:00Z".equals(q.get("capNhatTu"))) {
      reply(ex, 200, "{\"items\":[]}");
      return;
    }
    reply(
        ex,
        200,
        "{\"items\":["
            + "{\"maChiTieu\":\"GRDP\",\"kyBaoCao\":\"2026-Q2\",\"maDiaBan\":\"T01\",\"giaTri\":7.2,"
            + "\"donViTinh\":\"%\",\"daCongBo\":true,\"thoiDiemCongBo\":\"2026-07-15T09:00:00+07:00\","
            + "\"capNhatLuc\":\"2026-07-15T09:00:00+07:00\"},"
            + "{\"maChiTieu\":\"CPI\",\"kyBaoCao\":\"2026-Q3\",\"maDiaBan\":\"T01\",\"giaTri\":\"3.1\","
            + "\"donViTinh\":\"%\",\"daCongBo\":false,\"thoiDiemCongBo\":\"2026-10-01T09:00:00+07:00\","
            + "\"capNhatLuc\":\"2026-10-01T09:00:00+07:00\"},"
            + "{\"maChiTieu\":\"\",\"kyBaoCao\":\"2026\",\"maDiaBan\":\"T01\",\"giaTri\":1,"
            + "\"capNhatLuc\":\"2026-10-02T09:00:00+07:00\"}]}");
  }

  static Map<String, String> query(String raw) {
    Map<String, String> q = new HashMap<>();
    if (raw != null) {
      for (String p : raw.split("&")) {
        int eq = p.indexOf('=');
        if (eq > 0) {
          q.put(
              p.substring(0, eq),
              java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
    }
    return q;
  }

  static void reply(HttpExchange ex, int code, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(code, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @AfterAll
  static void stop() {
    NDXP.stop(0);
    ORG.stop(0);
    LGSP_SERVER.stop(0);
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
          connector(CBCCVC, "CBCCVC", base(NDXP))
              + connector(LGSP, "LGSP", base(LGSP_SERVER))
              + xref(CBCCVC, "DON_VI", "CQ-01", UNIT)
              + xref(CBCCVC, "CHUC_DANH", "CV", TITLE);
      admin.createStatement().execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String connector(UUID id, String code, String endpoint) {
    return "INSERT INTO integ.connector (id, tenant_id, created_by, updated_by, connector_code,"
        + " protocol, endpoint, secret_path, direction, enabled) VALUES ('%s', '%s',"
            .formatted(id, TENANT)
        + " gen_random_uuid(), gen_random_uuid(), '%s', 'REST', '%s', 'kv/integ/%s', 'VAO', true);"
            .formatted(code, endpoint, code.toLowerCase(Locale.ROOT));
  }

  static String xref(UUID connector, String type, String external, UUID internal) {
    return "INSERT INTO integ.mapping_xref (id, tenant_id, connector_id, entity_type, external_id,"
        + " internal_id, valid_from) VALUES (gen_random_uuid(), '%s', '%s', '%s', '%s', '%s',"
            .formatted(TENANT, connector, type, external, internal)
        + " DATE '2026-01-01');";
  }

  /** Mã hoá thật (AES-GCM) để chứng minh nguyên văn trong inbox không ở dạng rõ. */
  @TestConfiguration
  static class Fakes {
    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    @Primary
    PayloadCipher aesCipher() {
      SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
      SecureRandom random = new SecureRandom();
      return new PayloadCipher() {
        @Override
        public Sealed seal(TenantId tenant, String column, UUID rowId, byte[] plain) {
          try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] out = c.doFinal(plain);
            return new Sealed(
                ByteBuffer.allocate(12 + out.length).put(iv).put(out).array(),
                UUID.nameUUIDFromBytes(column.getBytes(StandardCharsets.UTF_8)));
          } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public byte[] open(TenantId tenant, String column, UUID rowId, byte[] ciphertext) {
          try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, ciphertext, 0, 12));
            return c.doFinal(ciphertext, 12, ciphertext.length - 12);
          } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
          }
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

  String gateway() {
    return "http://localhost:" + port + "/internal/v1/integ";
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(gateway() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  JsonNode run(UUID id) throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(gateway() + "/sync-runs/" + id + "?tenantId=" + TENANT))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return JSON.readTree(r.body());
  }

  boolean finished(UUID id) throws Exception {
    String s = run(id).path("status").asText();
    return "XONG".equals(s) || "LOI".equals(s);
  }

  static String start(UUID run) {
    return "{\"tenantId\":\"%s\",\"runId\":\"%s\",\"connector\":\"CBCCVC\"}".formatted(TENANT, run);
  }

  static final StringBuilder TIMING = new StringBuilder();

  @Test
  @Order(1)
  void dongBoNgayGianDoanGiuaChungThiLanChayLoi() throws Exception {
    long t0 = System.nanoTime();
    HttpResponse<String> r = post("/sync-runs", start(RUN_1));
    assertThat(r.statusCode()).isEqualTo(202);
    assertThat(r.headers().firstValue("Location"))
        .hasValue("/internal/v1/integ/sync-runs/" + RUN_1);
    await()
        .atMost(Duration.ofMinutes(20))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> finished(RUN_1));
    long t1 = System.nanoTime();
    JsonNode run = run(RUN_1);
    assertThat(run.path("status").asText()).isEqualTo("LOI");
    assertThat(run.path("pages").asInt()).isEqualTo(FAIL_AT / PAGE);
    assertThat(RECEIVED).hasSize(FAIL_AT);
    assertThat(RECORDS.get()).isEqualTo(FAIL_AT);
    assertThat(CLOSED)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.path("run").asText()).isEqualTo(RUN_1.toString());
              assertThat(c.path("reason").asText()).contains("IOException");
            });
    TIMING
        .append("Lần 1 (gián đoạn): ")
        .append(FAIL_AT)
        .append(" bản ghi, ")
        .append(Duration.ofNanos(t1 - t0).toMillis())
        .append(" ms\n");
  }

  @Test
  @Order(2)
  void lanSauChayTiepTuConTroKhongTrungKhongSot() throws Exception {
    NDXP_DOWN.set(false);
    long t0 = System.nanoTime();
    assertThat(post("/sync-runs", start(RUN_2)).statusCode()).isEqualTo(202);
    await()
        .atMost(Duration.ofMinutes(20))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> finished(RUN_2));
    long t1 = System.nanoTime();
    JsonNode run = run(RUN_2);
    assertThat(run.path("status").asText()).isEqualTo("XONG");
    assertThat(run.path("deadLetters").asInt()).isZero();
    assertThat(RECEIVED).hasSize(TOTAL);
    assertThat(RECEIVED.values()).allMatch(n -> n == 1);
    assertThat(RECORDS.get()).isEqualTo(TOTAL);
    assertThat(RUNS_SEEN).containsExactlyInAnyOrder(RUN_1.toString(), RUN_2.toString());
    assertThat(CLOSED).hasSize(2);
    assertThat(
            admin()
                .queryForObject(
                    "SELECT cursor_value FROM integ.sync_cursor WHERE connector_id = ?",
                    String.class,
                    CBCCVC))
        .isEqualTo(String.valueOf(TOTAL));
    assertThat(post("/sync-runs", start(RUN_2)).body()).contains("\"status\":\"XONG\"");
    TIMING
        .append("Lần 2 (chạy tiếp): ")
        .append(TOTAL - FAIL_AT)
        .append(" bản ghi, ")
        .append(Duration.ofNanos(t1 - t0).toMillis())
        .append(" ms\n");
    Path out = Path.of("target", "dv-13-06-thoi-gian-dong-bo.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, TIMING.toString());
    System.out.print("DV-13-06 thời gian đồng bộ\n" + TIMING);
  }

  @Test
  @Order(3)
  void chayLaiThuChetVaYeuCauSai() throws Exception {
    HttpResponse<String> retry =
        post(
            "/sync-runs/" + RUN_1 + "/retry",
            "{\"tenantId\":\"%s\",\"retryRunId\":\"%s\",\"connector\":\"CBCCVC\"}"
                .formatted(TENANT, RUN_RETRY));
    assertThat(retry.statusCode()).isEqualTo(202);
    assertThat(retry.body()).contains("\"kind\":\"CHAY_LAI\"");
    await().atMost(Duration.ofSeconds(30)).until(() -> finished(RUN_RETRY));
    assertThat(run(RUN_RETRY).path("status").asText()).isEqualTo("XONG");
    assertThat(CLOSED).hasSize(3);

    HttpResponse<String> scim =
        post(
            "/sync-runs",
            "{\"tenantId\":\"%s\",\"runId\":\"%s\",\"connector\":\"SCIM\"}"
                .formatted(TENANT, UUID.randomUUID()));
    assertThat(scim.statusCode()).isEqualTo(422);
    assertThat(scim.body()).contains("CONNECTOR_NOT_RUNNABLE");
    assertThat(post("/sync-runs", "{\"connector\":\"CBCCVC\"}").statusCode()).isEqualTo(400);
    assertThat(run(UUID.randomUUID()).path("status").asInt()).isEqualTo(404);
  }

  @Test
  @Order(4)
  void soDinhDanhKhongODangRo(CapturedOutput output) throws Exception {
    List<byte[]> raws =
        admin()
            .query(
                "SELECT raw_enc FROM integ.inbox_message WHERE connector_id = ?",
                (rs, i) -> rs.getBytes(1),
                CBCCVC);
    assertThat(raws).hasSize(TOTAL / PAGE);
    byte[] sample = nationalId(12_345).getBytes(StandardCharsets.UTF_8);
    for (byte[] raw : raws) {
      assertThat(indexOf(raw, sample)).isNegative();
    }
    assertThat(
            admin()
                .queryForObject(
                    "SELECT count(*) FROM integ.outbox_event WHERE position(? in convert_from(payload, 'LATIN1')) > 0",
                    Long.class,
                    nationalId(12_345)))
        .isZero();
    assertThat(
            admin()
                .queryForObject(
                    "SELECT count(*) FROM integ.dead_letter WHERE error_detail LIKE '%1099%'",
                    Long.class))
        .isZero();
    assertThat(output.getAll()).doesNotContain(nationalId(12_345), nationalId(99_999));
  }

  static int indexOf(byte[] data, byte[] pattern) {
    outer:
    for (int i = 0; i <= data.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  @Test
  @Order(5)
  void chiTieuKtxhChuaCongBoKhongDuocTinh() throws Exception {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                admin()
                        .queryForObject(
                            "SELECT count(*) FROM integ.ktxh_value WHERE connector_id = ?",
                            Long.class,
                            LGSP)
                    == 2);
    List<Map<String, Object>> rows =
        admin()
            .queryForList(
                "SELECT indicator_code, da_cong_bo, published_at FROM integ.ktxh_value"
                    + " ORDER BY indicator_code");
    assertThat(rows.get(0))
        .containsEntry("indicator_code", "CPI")
        .containsEntry("da_cong_bo", false);
    assertThat(rows.get(0).get("published_at")).isNull();
    assertThat(rows.get(1))
        .containsEntry("indicator_code", "GRDP")
        .containsEntry("da_cong_bo", true);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                admin()
                    .queryForList(
                        "SELECT error_code FROM integ.dead_letter WHERE connector_id = ?",
                        String.class,
                        LGSP)
                    .contains("CHI_TIEU_SAI_CAU_TRUC"));

    for (Object[] m : new Object[][] {{KR_GRDP, "GRDP"}, {KR_CPI, "CPI"}}) {
      HttpResponse<String> put =
          http.send(
              HttpRequest.newBuilder(URI.create(gateway() + "/mapping-xref"))
                  .header("Content-Type", "application/json")
                  .PUT(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"tenantId\":\"%s\",\"connector\":\"LGSP\",\"entityType\":\"CHI_SO\",\"externalId\":\"%s\",\"internalId\":\"%s\"}"
                              .formatted(TENANT, m[1], m[0])))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(put.statusCode()).isEqualTo(204);
    }
    HttpResponse<String> grdp = krValue(KR_GRDP);
    assertThat(grdp.statusCode()).isEqualTo(200);
    assertThat(grdp.body()).contains("\"value\":7.2", "\"fetchedAt\":\"2026-07-15T02:00:00Z\"");
    assertThat(krValue(KR_CPI).statusCode()).as("chỉ tiêu chưa công bố").isEqualTo(502);
  }

  HttpResponse<String> krValue(UUID kr) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(gateway() + "/kr-source-values?keyResultId=" + kr))
            .header("X-Tenant-Id", TENANT.toString())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
