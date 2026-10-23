package vn.viettel.okrkpi.evidence.infrastructure.ocr;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.evidence.application.FileNameCipher;
import vn.viettel.okrkpi.evidence.application.MalwareScanner;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.OcrService;
import vn.viettel.okrkpi.evidence.application.OcrTextCipher;
import vn.viettel.okrkpi.evidence.application.ScanService;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * Đọc chữ minh chứng qua ai-gateway giả lập (DV-08-05): PostgreSQL 17 có RLS với vai trò {@code
 * svc_evidence}, kho đối tượng và bộ quét giả trong bộ nhớ. Tệp sạch có văn bản lưu lại (mã hoá
 * C2); tệp nhiễm không được gửi đọc; tệp C3 không được xếp việc, bị cổng nội bộ từ chối và bị chặn
 * lần hai khi lọt vào hàng đợi; lỗi tạm thời của ai-gateway được thử lại có giới hạn.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.evidence.upload.sweep-enabled=false",
      "okrkpi.evidence.storage.endpoint=http://127.0.0.1:9",
      "okrkpi.evidence.storage.access-key=test",
      "okrkpi.evidence.storage.secret-key=test",
      "okrkpi.evidence.storage.lock-days=1",
      "okrkpi.evidence.scan.enabled=true",
      "okrkpi.evidence.scan.poll-interval=PT1H",
      "okrkpi.evidence.scan.clamd-host=127.0.0.1",
      "okrkpi.evidence.preview.gotenberg-url=http://127.0.0.1:9",
      "okrkpi.evidence.ocr.retry-base=PT0S",
      "okrkpi.evidence.ocr.max-attempts=3"
    })
class OcrFlowIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-0000000c0001");
  static final UUID USER = UUID.fromString("0192f1a2-0000-7000-8000-0000000c0002");
  static final String PDF = "application/pdf";
  static final String PNG = "image/png";
  static final String OCR = "/api/v1/ai/ocr";
  static final String NHIEM = "MAU-NHIEM-THU";

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_evidence");
  static final WireMockServer AI = new WireMockServer(wireMockConfig().dynamicPort());

  /** Kho đối tượng giả: khoá vật lý → nội dung. */
  static final Map<String, byte[]> BLOBS = new ConcurrentHashMap<>();

  static {
    POSTGRES.start();
    AI.start();
    migrate();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=evidence");
    r.add("spring.datasource.username", () -> "svc_evidence");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add("okrkpi.evidence.ocr.ai-gateway-url", AI::baseUrl);
  }

  static void migrate() {
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin = admin()) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    new SchemaMigrator(MigrationOptions.defaults())
        .migrate(
            POSTGRES.getJdbcUrl(),
            "evidence",
            new MigrationCredentials.Credentials("mig_evidence", PASSWORD));
  }

  static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    JwtDecoder testJwtDecoder() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject(USER.toString())
              .claim("tenant_id", TENANT.toString())
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(600))
              .build();
    }

    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    @Primary
    FileNameCipher fakeCipher() {
      return (tenant, id, name) ->
          new FileNameCipher.Sealed(name.getBytes(StandardCharsets.UTF_8), UUID.randomUUID());
    }

    /** Mã hoá giả có dấu để chứng minh văn bản đi qua bộ mã hoá trước khi ghi. */
    @Bean
    @Primary
    OcrTextCipher fakeOcrCipher() {
      return (tenant, version, text) ->
          new OcrTextCipher.Sealed(
              ("enc:" + text).getBytes(StandardCharsets.UTF_8), UUID.randomUUID());
    }

    @Bean
    @Primary
    MalwareScanner fakeScanner() {
      return new MalwareScanner() {
        @Override
        public Finding scan(java.io.InputStream content) {
          try {
            String s = new String(content.readAllBytes(), StandardCharsets.ISO_8859_1);
            return s.contains(NHIEM) ? new Finding(true, "Thu.Mau") : new Finding(false, null);
          } catch (IOException e) {
            throw new ScannerUnavailableException("đọc lỗi", e);
          }
        }

        @Override
        public String version() {
          return "1.0.0/1";
        }

        @Override
        public String engine() {
          return "ClamAV";
        }
      };
    }

    @Bean
    @Primary
    ObjectStorage fakeStorage() {
      return (ObjectStorage)
          java.lang.reflect.Proxy.newProxyInstance(
              ObjectStorage.class.getClassLoader(),
              new Class<?>[] {ObjectStorage.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "stream" -> {
                    ObjectLocation l = (ObjectLocation) args[0];
                    byte[] b = BLOBS.get(l.key());
                    if (b == null) {
                      throw new IOException("Không có đối tượng " + l.key());
                    }
                    return ((ObjectStorage.StreamReader<?>) args[2])
                        .read(new ByteArrayInputStream(b));
                  }
                  case "toString" -> {
                    return "kho-gia";
                  }
                  case "hashCode" -> {
                    return 0;
                  }
                  case "equals" -> {
                    return proxy == args[0];
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                }
              });
    }
  }

  @LocalServerPort int port;
  @Autowired ScanService scans;
  @Autowired OcrService ocr;

  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    AI.resetAll();
  }

  @Test
  void tepSachCoVanBanLuuLaiVaCongNoiBoCapNoiDung() throws Exception {
    byte[] file = pdf(3000);
    Ids f = insert(file, PDF, "C1");
    AI.stubFor(
        post(urlEqualTo(OCR))
            .withRequestBody(matchingJsonPath("$.objectKey", containing(f.object.toString())))
            .willReturn(ok("Biên bản nghiệm thu kết quả")));

    scans.scanDue(50);
    assertThat(column(f, "scan_status")).isEqualTo("SACH");
    assertThat(column(f, "ocr_status")).isEqualTo("CHO_DOC");

    HttpResponse<byte[]> content = internal(TENANT, f.object, 1);
    assertThat(content.statusCode()).isEqualTo(200);
    assertThat(content.body()).isEqualTo(file);
    assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(internal(TENANT, UUID.randomUUID(), 1).statusCode()).isEqualTo(404);
    assertThat(internal(UUID.randomUUID(), f.object, 1).statusCode()).isEqualTo(404);

    ocr.readDue(10);
    assertThat(column(f, "ocr_status")).isEqualTo("DA_DOC");
    AI.verify(
        1,
        postRequestedFor(urlEqualTo(OCR))
            .withHeader("Idempotency-Key", equalTo("ocr-" + f.version + "-1"))
            .withRequestBody(
                matchingJsonPath("$.objectKey", equalTo(TENANT + "/" + f.object + "/1")))
            .withRequestBody(matchingJsonPath("$.sha256", equalTo(sha(file))))
            .withRequestBody(matchingJsonPath("$.mimeType", equalTo(PDF)))
            .withRequestBody(matchingJsonPath("$.classification", equalTo("C1"))));
    try (Connection c = admin();
        var st =
            c.prepareStatement(
                "SELECT convert_from(text_enc, 'UTF8'), model_id, page_count, tenant_id"
                    + " FROM evidence.ocr_text WHERE object_version_id = ?")) {
      st.setObject(1, f.version);
      var rs = st.executeQuery();
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("enc:Biên bản nghiệm thu kết quả");
      assertThat(rs.getString(2)).isEqualTo("vt-ocr-thu");
      assertThat(rs.getInt(3)).isEqualTo(2);
      assertThat(rs.getObject(4, UUID.class)).isEqualTo(TENANT);
      assertThat(rs.next()).isFalse();
    }
    // Đã đọc xong thì không gọi lại.
    ocr.readDue(10);
    AI.verify(1, postRequestedFor(urlEqualTo(OCR)));
  }

  @Test
  void tepNhiemKhongDuocGuiDoc() throws Exception {
    byte[] file = pdf(3100);
    byte[] marker = NHIEM.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(marker, 0, file, 100, marker.length);
    Ids f = insert(file, PDF, "C1");

    scans.scanDue(50);
    assertThat(column(f, "scan_status")).isEqualTo("NHIEM");
    assertThat(column(f, "ocr_status")).isNull();
    assertThat(internal(TENANT, f.object, 1).statusCode()).isEqualTo(403);
    ocr.readDue(10);
    AI.verify(0, postRequestedFor(urlEqualTo(OCR)));
  }

  @Test
  void tepC3KhongXepViecBiTuChoiVaBiChanLanHai() throws Exception {
    Ids f = insert(new byte[700], "application/octet-stream", "C3");

    scans.scanDue(50);
    assertThat(column(f, "scan_status")).isEqualTo("SACH");
    assertThat(column(f, "ocr_status")).isNull();
    assertThat(internal(TENANT, f.object, 1).statusCode()).isEqualTo(403);

    // Ép tệp C3 vào hàng đợi: worker không gửi đi mà kết luận lỗi.
    exec(
        "UPDATE evidence.object_version SET ocr_status = 'CHO_DOC', ocr_next_at = now()"
            + " WHERE id = '"
            + f.version
            + "'");
    ocr.readDue(10);
    assertThat(column(f, "ocr_status")).isEqualTo("LOI");
    AI.verify(0, postRequestedFor(urlEqualTo(OCR)));
  }

  @Test
  void loiTamThoiDuocThuLaiCoGioiHan() throws Exception {
    Ids f = insert(png(2400), PNG, "C2");
    AI.stubFor(post(urlEqualTo(OCR)).willReturn(aResponse().withStatus(503)));

    scans.scanDue(50);
    assertThat(column(f, "ocr_status")).isEqualTo("CHO_DOC");
    for (int i = 0; i < 5; i++) {
      ocr.readDue(10);
    }
    assertThat(column(f, "ocr_status")).isEqualTo("LOI");
    assertThat(column(f, "ocr_attempts")).isEqualTo("3");
    AI.verify(3, postRequestedFor(urlEqualTo(OCR)));
    for (int i = 1; i <= 3; i++) {
      AI.verify(
          1,
          postRequestedFor(urlEqualTo(OCR))
              .withHeader("Idempotency-Key", equalTo("ocr-" + f.version + "-" + i)));
    }
  }

  @Test
  void anhKhongDuNetThiKhongDocDuoc() throws Exception {
    Ids f = insert(png(2500), PNG, "C1");
    AI.stubFor(
        post(urlEqualTo(OCR))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"status\":422,\"code\":\"AI-422-OCR-UNREADABLE\"}")));

    scans.scanDue(50);
    ocr.readDue(10);
    assertThat(column(f, "ocr_status")).isEqualTo("KHONG_DOC_DUOC");
    ocr.readDue(10);
    AI.verify(1, postRequestedFor(urlEqualTo(OCR)));
  }

  // ---------------------------------------------------------------- tiện ích

  record Ids(UUID object, UUID version) {}

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder ok(String text) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(
            "{\"objectKey\":\"x\",\"sha256\":\"x\",\"pages\":[{\"page\":1,\"text\":\"a\"},"
                + "{\"page\":2,\"text\":\"b\"}],\"fullText\":\""
                + text
                + "\",\"modelId\":\"vt-ocr-thu\",\"processedAt\":\"2026-10-23T01:02:03Z\"}");
  }

  /** Ghi thẳng một tệp đang chờ quét (bỏ qua luồng tải lên đã có kiểm thử riêng). */
  static Ids insert(byte[] content, String mediaType, String cls) throws Exception {
    UUID object = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    String key = "t/" + TENANT + "/sha256/" + sha(content);
    BLOBS.put(key, content);
    try (Connection c = admin()) {
      try (var st =
          c.prepareStatement(
              "INSERT INTO evidence.object_meta (id, tenant_id, created_by, updated_by,"
                  + " owner_module, file_name_enc, dek_id, media_type, classification,"
                  + " retention_class) VALUES (?, ?, ?, ?, 'product', ?, ?, ?, ?, 'TIEU_CHUAN')")) {
        st.setObject(1, object);
        st.setObject(2, TENANT);
        st.setObject(3, USER);
        st.setObject(4, USER);
        st.setBytes(5, "tep".getBytes(StandardCharsets.UTF_8));
        st.setObject(6, UUID.randomUUID());
        st.setString(7, mediaType);
        st.setString(8, cls);
        st.executeUpdate();
      }
      try (var st =
          c.prepareStatement(
              "INSERT INTO evidence.object_version (id, tenant_id, object_meta_id, version_no,"
                  + " sha256, size_bytes, bucket, object_key, ceph_version_id, created_by)"
                  + " VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'v1', ?)")) {
        st.setObject(1, version);
        st.setObject(2, TENANT);
        st.setObject(3, object);
        st.setBytes(4, MessageDigest.getInstance("SHA-256").digest(content));
        st.setLong(5, content.length);
        st.setString(6, "ev-dev-" + TENANT);
        st.setString(7, key);
        st.setObject(8, USER);
        st.executeUpdate();
      }
      try (var st =
          c.prepareStatement(
              "UPDATE evidence.object_meta SET current_version_id = ? WHERE id = ?")) {
        st.setObject(1, version);
        st.setObject(2, object);
        st.executeUpdate();
      }
    }
    return new Ids(object, version);
  }

  static String column(Ids f, String name) throws SQLException {
    try (Connection c = admin();
        var st =
            c.prepareStatement(
                "SELECT " + name + "::text FROM evidence.object_version WHERE id = ?")) {
      st.setObject(1, f.version);
      var rs = st.executeQuery();
      rs.next();
      return rs.getString(1);
    }
  }

  static void exec(String sql) throws SQLException {
    try (Connection c = admin();
        var st = c.createStatement()) {
      st.execute(sql);
    }
  }

  HttpResponse<byte[]> internal(UUID tenant, UUID object, int versionNo) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/internal/v1/ocr-objects/"
                        + tenant
                        + "/"
                        + object
                        + "/"
                        + versionNo))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  static byte[] pdf(int size) {
    byte[] b = new byte[size];
    new Random(size).nextBytes(b);
    byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(head, 0, b, 0, head.length);
    return b;
  }

  static byte[] png(int size) {
    byte[] b = new byte[size];
    new Random(size).nextBytes(b);
    byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    System.arraycopy(head, 0, b, 0, head.length);
    return b;
  }

  static String sha(byte[] data) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}
