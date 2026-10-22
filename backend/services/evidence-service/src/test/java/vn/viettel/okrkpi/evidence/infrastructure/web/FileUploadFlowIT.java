package vn.viettel.okrkpi.evidence.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.application.OpenApiConformanceEngine;
import vn.viettel.okrkpi.evidence.application.FileNameCipher;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.ScanQueue;
import vn.viettel.okrkpi.evidence.application.ScanService;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.evidence.infrastructure.events.OutboxScanEvents;
import vn.viettel.okrkpi.evidence.infrastructure.scanning.ClamdScanner;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Tải lên nhiều phần qua HTTP thật (DV-08-02): PostgreSQL 17 có RLS với vai trò {@code
 * svc_evidence}, S3 giả lập LocalStack có Object Lock. Tải 3 phần, ngắt ở phần 2 rồi tải tiếp; băm
 * sai, kiểu sai bị từ chối; xoá đối tượng bị kho từ chối; outbox đúng một sự kiện; phản hồi khớp
 * hợp đồng; tệp 50 MB không làm heap tăng theo kích thước tệp.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "okrkpi.opa.enabled=false",
      "okrkpi.crypto.enabled=false",
      "okrkpi.evidence.upload.sweep-enabled=false",
      "okrkpi.evidence.upload.part-size=5242880",
      "okrkpi.evidence.storage.access-key=test",
      "okrkpi.evidence.storage.secret-key=test",
      "okrkpi.evidence.storage.lock-days=1",
      // Worker bật nhưng lịch không chạy trong lúc thử: kiểm thử gọi ScanService trực tiếp.
      "okrkpi.evidence.scan.enabled=true",
      "okrkpi.evidence.scan.poll-interval=PT1H"
    })
class FileUploadFlowIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final int PART = 5 * 1024 * 1024;
  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-0000000e0001");
  static final UUID USER = UUID.fromString("0192f1a2-0000-7000-8000-0000000e0002");
  static final UUID OTHER = UUID.fromString("0192f1a2-0000-7000-8000-0000000e0003");
  static final String PDF = "application/pdf";

  static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("mission_evidence");

  static final GenericContainer<?> S3 =
      new GenericContainer<>("localstack/localstack:4.4")
          .withEnv("SERVICES", "s3")
          .withExposedPorts(4566)
          .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));

  static final GenericContainer<?> CLAMAV =
      new GenericContainer<>("clamav/clamav:1.4")
          .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
          .withExposedPorts(3310)
          .waitingFor(
              Wait.forLogMessage(".*socket found, clamd started.*", 1)
                  .withStartupTimeout(java.time.Duration.ofMinutes(3)));

  static {
    POSTGRES.start();
    S3.start();
    CLAMAV.start();
    migrate();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=evidence");
    r.add("spring.datasource.username", () -> "svc_evidence");
    r.add("spring.datasource.password", () -> PASSWORD);
    r.add(
        "okrkpi.evidence.storage.endpoint",
        () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(4566));
    r.add("okrkpi.evidence.scan.clamd-host", CLAMAV::getHost);
    r.add("okrkpi.evidence.scan.clamd-port", () -> CLAMAV.getMappedPort(3310));
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

  /** Token thử: {@code <sub>.<tenant>}; không cần Keycloak. */
  @TestConfiguration
  static class Fakes {
    @Bean
    JwtDecoder testJwtDecoder() {
      return token -> {
        String[] p = token.split("\\.");
        return Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject(p[0])
            .claim("tenant_id", p[1])
            .claim("realm_access", Map.of("roles", List.of("member")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
      };
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
  }

  @LocalServerPort int port;
  @Autowired S3Client s3;
  @Autowired UploadService uploads;
  @Autowired ScanService scans;
  @Autowired ScanQueue scanQueue;
  @Autowired OutboxWriter outbox;
  @Autowired ObjectStorage storage;
  @Autowired PlatformTransactionManager transactions;

  final ObjectMapper json = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();
  static OpenAPI contract;

  /** Tenant của người gọi trong các yêu cầu tiếp theo. */
  UUID tenant = TENANT;

  @BeforeAll
  static void loadContract() {
    contract = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.PRODUCTS);
  }

  @Test
  void taiBaPhanNgatOPhanHaiRoiTaiTiepThiLuuVaKhoa() throws Exception {
    byte[] file = pdf(2 * PART + 1234);
    JsonNode session = create(USER, file, PDF, "C1", null);
    String uploadId = session.get("uploadId").asText();
    assertThat(session.get("partCount").asInt()).isEqualTo(3);

    String etag1 = putPart(USER, uploadId, 1, file).get("etag").asText();
    assertThat(interruptedPart(USER, uploadId, 2, file)).isTrue();
    JsonNode resumed = getJson(USER, "/files/uploads/" + uploadId, 200);
    conforms("/files/uploads/{uploadId}", PathItem.HttpMethod.GET, 200, resumed);
    assertThat(resumed.get("receivedParts")).hasSize(1);
    assertThat(resumed.get("receivedParts").get(0).get("partNo").asInt()).isEqualTo(1);

    String etag2 = putPart(USER, uploadId, 2, file).get("etag").asText();
    String etag3 = putPart(USER, uploadId, 3, file).get("etag").asText();
    JsonNode done = complete(USER, uploadId, List.of(etag1, etag2, etag3), 200);
    conforms("/files/uploads/{uploadId}/complete", PathItem.HttpMethod.POST, 200, done);
    assertThat(done.get("objectId").asText()).isEqualTo(uploadId);
    assertThat(done.get("sha256").asText()).isEqualTo(sha(file));
    assertThat(done.get("scanStatus").asText()).isEqualTo("CHO_QUET");
    assertThat(done.get("sizeBytes").asLong()).isEqualTo(file.length);

    // Gọi lại sau khi xong trả cùng đối tượng.
    assertThat(complete(USER, uploadId, List.of(etag1, etag2, etag3), 200).get("objectVersionId"))
        .isEqualTo(done.get("objectVersionId"));

    try (Connection c = admin();
        var st = c.createStatement()) {
      var rs =
          st.executeQuery(
              "SELECT count(*) FROM evidence.outbox_event WHERE aggregate_id = '"
                  + uploadId
                  + "' AND topic = 'sys.evidence.object.stored.v1'");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
      rs =
          st.executeQuery(
              "SELECT bucket, object_key, ceph_version_id FROM evidence.object_version"
                  + " WHERE object_meta_id = '"
                  + uploadId
                  + "'");
      rs.next();
      String bucket = rs.getString(1);
      String key = rs.getString(2);
      String version = rs.getString(3);
      assertThat(bucket).isEqualTo("ev-dev-" + TENANT);
      assertThat(key)
          .isEqualTo("t/" + TENANT + "/sha256/" + sha(file).substring(0, 2) + "/" + sha(file));
      assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(key).versionId(version)))
          .isInstanceOf(S3Exception.class);
      assertThat(
              s3.getObjectAsBytes(b -> b.bucket(bucket).key(key).versionId(version)).asByteArray())
          .isEqualTo(file);
    }
  }

  @Test
  void cungTepHaiNguoiMotDoiTuongVatLyKhacTenantKhongTrung() throws Exception {
    byte[] file = pdf(4321);
    JsonNode a = upload(USER, file);
    assertThat(a.get("dedup").get("duplicate").asBoolean()).isFalse();
    JsonNode b = upload(OTHER, file);
    conforms("/files/uploads/{uploadId}/complete", PathItem.HttpMethod.POST, 200, b);
    assertThat(b.get("dedup").get("duplicate").asBoolean()).isTrue();

    UUID foreign = UUID.randomUUID();
    tenant = foreign;
    JsonNode c;
    try {
      c = upload(USER, file);
    } finally {
      tenant = TENANT;
    }
    assertThat(c.get("dedup").get("duplicate").asBoolean()).isFalse();

    try (Connection conn = admin();
        var st = conn.createStatement()) {
      var rs =
          st.executeQuery(
              "SELECT count(DISTINCT (bucket, object_key, ceph_version_id)) FROM"
                  + " evidence.object_version WHERE object_meta_id IN ('"
                  + a.get("objectId").asText()
                  + "', '"
                  + b.get("objectId").asText()
                  + "')");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
      rs =
          st.executeQuery(
              "SELECT tenant_id, ref_count, owners_sample::text FROM evidence.dedup_index"
                  + " WHERE sha256 = decode('"
                  + sha(file)
                  + "', 'hex') ORDER BY ref_count DESC");
      rs.next();
      assertThat(rs.getObject(1, UUID.class)).isEqualTo(TENANT);
      assertThat(rs.getInt(2)).isEqualTo(2);
      assertThat(rs.getString(3)).isEqualTo("{" + USER + "," + OTHER + "}");
      rs.next();
      assertThat(rs.getObject(1, UUID.class)).isEqualTo(foreign);
      assertThat(rs.getInt(2)).isEqualTo(1);
      assertThat(rs.next()).isFalse();
      // Tín hiệu minh chứng trùng đi trong sự kiện lưu đối tượng (băm, người tải); SVC-15 phát
      // EVT-20.
      rs =
          st.executeQuery(
              "SELECT count(*) FILTER (WHERE topic = 'sys.evidence.object.stored.v1'),"
                  + " count(*) FILTER (WHERE topic <> 'sys.evidence.object.stored.v1')"
                  + " FROM evidence.outbox_event WHERE aggregate_id IN ('"
                  + a.get("objectId").asText()
                  + "', '"
                  + b.get("objectId").asText()
                  + "')");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(2);
      assertThat(rs.getInt(2)).isZero();
    }
    String key = "t/" + TENANT + "/sha256/" + sha(file).substring(0, 2) + "/" + sha(file);
    assertThat(s3.listObjectVersions(b2 -> b2.bucket("ev-dev-" + TENANT).prefix(key)).versions())
        .hasSize(1);
  }

  @Test
  void haiMuoiYeuCauDongThoiCungBamChoMotDongChiMuc() throws Exception {
    byte[] file = pdf(5432);
    List<String[]> ready = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      String id = create(USER, file, PDF, "C1", null).get("uploadId").asText();
      ready.add(new String[] {id, putPart(USER, id, 1, file).get("etag").asText()});
    }
    var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      List<java.util.concurrent.Future<JsonNode>> all = new ArrayList<>();
      for (String[] r : ready) {
        all.add(
            pool.submit(
                () -> {
                  start.await();
                  return complete(USER, r[0], List.of(r[1]), 200);
                }));
      }
      start.countDown();
      for (var f : all) {
        assertThat(f.get().get("sha256").asText()).isEqualTo(sha(file));
      }
    } finally {
      pool.shutdownNow();
    }
    try (Connection conn = admin();
        var st = conn.createStatement()) {
      var rs =
          st.executeQuery(
              "SELECT count(*), sum(ref_count), max(cardinality(owners_sample))"
                  + " FROM evidence.dedup_index WHERE sha256 = decode('"
                  + sha(file)
                  + "', 'hex')");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
      assertThat(rs.getInt(2)).isEqualTo(20);
      assertThat(rs.getInt(3)).isEqualTo(1);
    }
  }

  static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  static final String EICAR =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

  @Test
  void quetNenChoSachNhiemCachLyVaC3KhongApDung() throws Exception {
    JsonNode clean = upload(USER, pdf(6100));
    String cleanId = clean.get("objectId").asText();
    JsonNode waiting = getJson(USER, "/files/" + cleanId + "/scan", 200);
    conforms("/files/{objectId}/scan", PathItem.HttpMethod.GET, 200, waiting);
    assertThat(waiting.get("scanStatus").asText()).isEqualTo("CHO_QUET");
    assertThat(waiting.hasNonNull("verdict")).isFalse();

    byte[] infected = docx(Map.of("word/media/eicar.com", EICAR));
    String infectedId = uploadAs(USER, infected, DOCX).get("objectId").asText();
    HttpResponse<String> c3 =
        post(
            USER,
            "/files/uploads",
            createBody(
                "ban-ma.bin", "application/octet-stream", 700, sha(new byte[700]), "C3", true));
    String c3Id = json.readTree(c3.body()).get("uploadId").asText();
    complete(USER, c3Id, List.of(putPart(USER, c3Id, 1, new byte[700]).get("etag").asText()), 200);

    long started = System.nanoTime();
    scans.scanDue(500);
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    System.out.println("DV-08-04 quét một lô gồm tệp sạch, tệp nhiễm, tệp C3: " + tookMs + " ms");

    JsonNode ok = getJson(USER, "/files/" + cleanId + "/scan", 200);
    conforms("/files/{objectId}/scan", PathItem.HttpMethod.GET, 200, ok);
    assertThat(ok.get("scanStatus").asText()).isEqualTo("SACH");
    assertThat(ok.get("verdict").asText()).isEqualTo("SACH");
    assertThat(ok.get("engine").asText()).isEqualTo("ClamAV");
    assertThat(ok.get("engineVersion").asText()).matches("\\d+\\.\\d+\\.\\d+/\\d+");
    assertThat(getJson(USER, "/files/" + cleanId + "/download-url", 200).get("url")).isNotNull();

    JsonNode bad = getJson(USER, "/files/" + infectedId + "/scan", 200);
    assertThat(bad.get("scanStatus").asText()).isEqualTo("NHIEM");
    assertThat(bad.get("verdict").asText()).isEqualTo("NHIEM");
    assertThat(getJson(USER, "/files/" + infectedId + "/download-url", 409).get("code").asText())
        .isEqualTo("FILE-409-NOT-CLEAN");

    JsonNode device = getJson(USER, "/files/" + c3Id + "/scan", 200);
    assertThat(device.get("scanStatus").asText()).isEqualTo("SACH");
    assertThat(device.get("engine").asText()).isEqualTo(ScanService.DEVICE_ENGINE);

    try (Connection conn = admin();
        var st = conn.createStatement()) {
      var rs =
          st.executeQuery(
              "SELECT aggregate_id::text, topic FROM evidence.outbox_event WHERE aggregate_id IN ('"
                  + cleanId
                  + "', '"
                  + infectedId
                  + "', '"
                  + c3Id
                  + "') AND topic <> 'sys.evidence.object.stored.v1' ORDER BY topic, aggregate_id");
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(rs.getString(1) + " " + rs.getString(2));
      }
      assertThat(rows)
          .containsExactlyInAnyOrder(
              cleanId + " evidence.object.scanned.v1",
              infectedId + " evidence.object.scanned.v1",
              c3Id + " evidence.object.scanned.v1",
              infectedId + " notify.message.requested.v1");
      rs =
          st.executeQuery(
              "SELECT detail->>'mau' FROM evidence.scan_result r JOIN evidence.object_version v"
                  + " ON v.id = r.object_version_id WHERE v.object_meta_id = '"
                  + infectedId
                  + "'");
      rs.next();
      assertThat(rs.getString(1)).contains("Eicar");
    }

    // Quét lại không đổi kết luận đã có.
    scans.scanDue(500);
    assertThat(getJson(USER, "/files/" + infectedId + "/scan", 200).get("scanStatus").asText())
        .isEqualTo("NHIEM");
  }

  @Test
  void docxCoMacroBiChanKhiNhan() throws Exception {
    byte[] macro = docx(Map.of("word/vbaProject.bin", "Attribute VB_Name"));
    String id = create(USER, macro, DOCX, "C1", null).get("uploadId").asText();
    String etag = putPart(USER, id, 1, macro).get("etag").asText();
    assertThat(complete(USER, id, List.of(etag), 422).get("code").asText())
        .isEqualTo("FILE-422-CONTENT-TYPE-MISMATCH");
  }

  @Test
  void boQuetTatThiLoiSauSoLanThu() throws Exception {
    String id = upload(USER, pdf(6200)).get("objectId").asText();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    int closedPort;
    try (var probe = new java.net.ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }
    ScanService broken =
        new ScanService(
            scanQueue,
            new OutboxScanEvents(outbox),
            storage,
            new ClamdScanner("127.0.0.1", closedPort, java.time.Duration.ofSeconds(1)),
            new TransactionTemplate(transactions),
            new ScanService.Settings(java.time.Duration.ofMinutes(10), java.time.Duration.ZERO, 3),
            java.time.Clock.systemUTC(),
            meters);
    for (int i = 0; i < 3; i++) {
      assertThat(getJson(USER, "/files/" + id + "/scan", 200).get("scanStatus").asText())
          .isEqualTo("CHO_QUET");
      broken.scanDue(500);
    }
    JsonNode failed = getJson(USER, "/files/" + id + "/scan", 200);
    assertThat(failed.get("scanStatus").asText()).isEqualTo("LOI");
    assertThat(failed.get("verdict").asText()).isEqualTo("LOI");
    assertThat(getJson(USER, "/files/" + id + "/download-url", 409).get("code").asText())
        .isEqualTo("FILE-409-NOT-CLEAN");
    assertThat(meters.counter("evidence.scan.failures").count()).isGreaterThanOrEqualTo(1);
    try (Connection conn = admin();
        var st = conn.createStatement()) {
      var rs =
          st.executeQuery(
              "SELECT scan_attempts FROM evidence.object_version WHERE object_meta_id = '"
                  + id
                  + "'");
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(3);
    }
  }

  @Test
  void bamSaiKieuSaiBiTuChoi() throws Exception {
    byte[] file = pdf(1000);
    JsonNode s = create(USER, file, PDF, "C1", "ab".repeat(32));
    String id = s.get("uploadId").asText();
    String etag = putPart(USER, id, 1, file).get("etag").asText();
    JsonNode problem = complete(USER, id, List.of(etag), 422);
    assertThat(problem.get("code").asText()).isEqualTo("FILE-422-HASH-MISMATCH");
    assertThat(getJson(USER, "/files/uploads/" + id, 200).get("status").asText()).isEqualTo("HUY");

    byte[] png = new byte[2000];
    System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, 0, png, 0, 8);
    s = create(USER, png, PDF, "C1", null);
    id = s.get("uploadId").asText();
    etag = putPart(USER, id, 1, png).get("etag").asText();
    assertThat(complete(USER, id, List.of(etag), 422).get("code").asText())
        .isEqualTo("FILE-422-CONTENT-TYPE-MISMATCH");
  }

  @Test
  void gioiHanQuyenVaHuyPhien() throws Exception {
    HttpResponse<String> big =
        post(
            USER,
            "/files/uploads",
            createBody("a.pdf", PDF, 60L * 1024 * 1024, sha(new byte[1]), "C1", false));
    assertThat(big.statusCode()).isEqualTo(413);
    assertThat(json.readTree(big.body()).get("code").asText()).isEqualTo("FILE-413-TOO-LARGE");

    byte[] file = pdf(500);
    String id = create(USER, file, PDF, "C1", null).get("uploadId").asText();
    HttpResponse<String> stranger = putRaw(OTHER, id, 1, file);
    assertThat(stranger.statusCode()).isEqualTo(403);
    HttpResponse<String> foreignTenant =
        send(
            HttpRequest.newBuilder(api("/files/uploads/" + id))
                .header("Authorization", "Bearer " + USER + "." + UUID.randomUUID())
                .GET());
    assertThat(foreignTenant.statusCode()).isEqualTo(404);

    HttpResponse<String> cancel =
        send(
            HttpRequest.newBuilder(api("/files/uploads/" + id))
                .header("Authorization", bearer(USER))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .DELETE());
    assertThat(cancel.statusCode()).isEqualTo(204);
    HttpResponse<String> late = putRaw(USER, id, 1, file);
    assertThat(late.statusCode()).isEqualTo(409);
    assertThat(json.readTree(late.body()).get("code").asText()).isEqualTo("FILE-409-UPLOAD-CLOSED");
  }

  @Test
  void urlTaiChiChoTepSachVaSongNamPhut() throws Exception {
    byte[] file = pdf(3000);
    String id = create(USER, file, PDF, "C2", null).get("uploadId").asText();
    String etag = putPart(USER, id, 1, file).get("etag").asText();
    complete(USER, id, List.of(etag), 200);

    JsonNode notClean = getJson(USER, "/files/" + id + "/download-url", 409);
    assertThat(notClean.get("code").asText()).isEqualTo("FILE-409-NOT-CLEAN");

    try (Connection c = admin();
        var st = c.createStatement()) {
      st.execute(
          "UPDATE evidence.object_version SET scan_status = 'SACH' WHERE object_meta_id = '"
              + id
              + "'");
    }
    HttpResponse<String> r =
        send(
            HttpRequest.newBuilder(api("/files/" + id + "/download-url"))
                .header("Authorization", bearer(USER))
                .GET());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Cache-Control"))
        .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    JsonNode link = json.readTree(r.body());
    conforms("/files/{objectId}/download-url", PathItem.HttpMethod.GET, 200, link);
    Instant expires = Instant.parse(link.get("expiresAt").asText());
    assertThat(expires).isBetween(Instant.now().plusSeconds(240), Instant.now().plusSeconds(301));
    byte[] downloaded =
        http.send(
                HttpRequest.newBuilder(URI.create(link.get("url").asText())).GET().build(),
                BodyHandlers.ofByteArray())
            .body();
    assertThat(downloaded).isEqualTo(file);
  }

  @Test
  void jobDonPhienQuaHan() throws Exception {
    byte[] file = pdf(800);
    String id = create(USER, file, PDF, "C1", null).get("uploadId").asText();
    putPart(USER, id, 1, file);
    try (Connection c = admin();
        var st = c.createStatement()) {
      st.execute(
          "UPDATE evidence.upload_session SET expires_at = now() - interval '1 minute' WHERE id = '"
              + id
              + "'");
    }
    assertThat(uploads.expireStale(50)).isGreaterThanOrEqualTo(1);
    assertThat(getJson(USER, "/files/uploads/" + id, 200).get("status").asText()).isEqualTo("HUY");
    JdbcTemplate admin =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(admin(), true));
    assertThat(
            admin.queryForObject(
                "SELECT rgw_upload_id FROM evidence.upload_session WHERE id = ?::uuid",
                String.class,
                id))
        .isNotBlank();
    assertThat(s3.listMultipartUploads(b -> b.bucket("ev-dev-tam")).uploads())
        .noneMatch(u -> u.key().endsWith(id));
  }

  @Test
  void tep50MbKhongDemCaTepVaoHeap() throws Exception {
    long size = 50L * 1024 * 1024;
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] block = pdf(PART);
    long left = size;
    while (left > 0) {
      int n = (int) Math.min(left, block.length);
      md.update(block, 0, n);
      left -= n;
    }
    String hash = HexFormat.of().formatHex(md.digest());
    HttpResponse<String> created =
        post(USER, "/files/uploads", createBody("lon.pdf", PDF, size, hash, "C1", false));
    assertThat(created.statusCode()).isEqualTo(201);
    String id = json.readTree(created.body()).get("uploadId").asText();
    int parts = (int) ((size + PART - 1) / PART);

    // Mảng từ nửa vùng G1 trở lên (bộ đệm cả phần hay cả tệp) cấp thẳng vào vùng lớn của thế hệ cũ,
    // còn bộ đệm vào ra nhỏ chết ở vùng trẻ; nên đỉnh thế hệ cũ cho biết có đệm theo kích thước
    // tệp.
    MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
    MemoryPoolMXBean old =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(p -> p.getType() == MemoryType.HEAP && p.getName().contains("Old"))
            .findFirst()
            .orElseThrow();
    System.gc();
    long baseline = mem.getHeapMemoryUsage().getUsed();
    long oldBaseline = old.getUsage().getUsed();
    AtomicLong peak = new AtomicLong(baseline);
    AtomicLong oldPeak = new AtomicLong(oldBaseline);
    AtomicBoolean running = new AtomicBoolean(true);
    Thread sampler =
        Thread.ofPlatform()
            .start(
                () -> {
                  while (running.get()) {
                    peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(), Math::max);
                    oldPeak.accumulateAndGet(old.getUsage().getUsed(), Math::max);
                    try {
                      Thread.sleep(2);
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                });
    List<String> etags = new ArrayList<>();
    for (int n = 1; n <= parts; n++) {
      long len = Math.min(PART, size - (long) (n - 1) * PART);
      HttpResponse<String> r =
          send(
              HttpRequest.newBuilder(api("/files/uploads/" + id + "/parts/" + n))
                  .header("Authorization", bearer(USER))
                  .header("Idempotency-Key", id + "-" + n)
                  .header("Content-Type", "application/octet-stream")
                  .PUT(
                      BodyPublishers.fromPublisher(
                          BodyPublishers.ofInputStream(() -> repeat(block, len)), len)));
      assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
      etags.add(json.readTree(r.body()).get("etag").asText());
    }
    JsonNode done = complete(USER, id, etags, 200);
    running.set(false);
    sampler.join();
    System.gc();
    long after = mem.getHeapMemoryUsage().getUsed();
    long oldGrowth = oldPeak.get() - oldBaseline;
    System.out.printf(
        Locale.ROOT,
        "DV-08-02 đo heap khi tải 50 MB: nền %d KB, đỉnh %d KB (gồm rác vùng trẻ), thế hệ cũ tăng"
            + " tối đa %d KB, sau GC tăng %d KB%n",
        baseline >> 10,
        peak.get() >> 10,
        oldGrowth >> 10,
        (after - baseline) >> 10);
    assertThat(done.get("sha256").asText()).isEqualTo(hash);
    assertThat(oldGrowth).isLessThan(PART);
    assertThat(after - baseline).isLessThan(PART);
  }

  // ---------------------------------------------------------------- tiện ích

  static InputStream repeat(byte[] block, long len) {
    return new InputStream() {
      long pos;

      @Override
      public int read() {
        return pos < len ? block[(int) (pos++ % block.length)] & 0xff : -1;
      }

      @Override
      public int read(byte[] b, int off, int n) {
        if (pos >= len) {
          return -1;
        }
        int k = (int) Math.min(n, Math.min(len - pos, block.length - pos % block.length));
        System.arraycopy(block, (int) (pos % block.length), b, off, k);
        pos += k;
        return k;
      }
    };
  }

  /** Tải trọn một tệp nhỏ hơn một phần và trả kết quả hoàn tất. */
  JsonNode upload(UUID user, byte[] file) throws Exception {
    return uploadAs(user, file, PDF);
  }

  JsonNode uploadAs(UUID user, byte[] file, String type) throws Exception {
    String id = create(user, file, type, "C1", null).get("uploadId").asText();
    return complete(user, id, List.of(putPart(user, id, 1, file).get("etag").asText()), 200);
  }

  JsonNode create(UUID user, byte[] file, String type, String cls, String shaOverride)
      throws Exception {
    HttpResponse<String> r =
        post(
            user,
            "/files/uploads",
            createBody(
                "tep.pdf",
                type,
                file.length,
                shaOverride == null ? sha(file) : shaOverride,
                cls,
                false));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    assertThat(r.headers().firstValue("Location")).isPresent();
    JsonNode body = json.readTree(r.body());
    conforms("/files/uploads", PathItem.HttpMethod.POST, 201, body);
    return body;
  }

  String createBody(String name, String type, long size, String sha, String cls, boolean e2ee)
      throws Exception {
    return json.writeValueAsString(
        Map.of(
            "fileName", name,
            "mediaType", type,
            "declaredSize", size,
            "declaredSha256", sha,
            "ownerModule", "product",
            "classification", cls,
            "e2ee", e2ee));
  }

  JsonNode putPart(UUID user, String id, int n, byte[] file) throws Exception {
    HttpResponse<String> r = putRaw(user, id, n, file);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    JsonNode body = json.readTree(r.body());
    conforms("/files/uploads/{uploadId}/parts/{n}", PathItem.HttpMethod.PUT, 200, body);
    return body;
  }

  HttpResponse<String> putRaw(UUID user, String id, int n, byte[] file) throws Exception {
    int from = (n - 1) * PART;
    int to = Math.min(file.length, from + PART);
    byte[] chunk = java.util.Arrays.copyOfRange(file, from, to);
    return send(
        HttpRequest.newBuilder(api("/files/uploads/" + id + "/parts/" + n))
            .header("Authorization", bearer(user))
            .header("Idempotency-Key", id + "-" + n)
            .header("Content-Type", "application/octet-stream")
            .PUT(BodyPublishers.ofByteArray(chunk)));
  }

  /** Gửi một nửa phần rồi cắt kết nối, như mất mạng giữa chừng. */
  boolean interruptedPart(UUID user, String id, int n, byte[] file) {
    int from = (n - 1) * PART;
    int len = Math.min(PART, file.length - from);
    Flow.Publisher<java.nio.ByteBuffer> half =
        subscriber ->
            subscriber.onSubscribe(
                new Flow.Subscription() {
                  boolean sent;

                  @Override
                  public void request(long k) {
                    if (!sent) {
                      sent = true;
                      subscriber.onNext(java.nio.ByteBuffer.wrap(file, from, len / 2));
                      subscriber.onError(new IOException("Mất kết nối thử nghiệm"));
                    }
                  }

                  @Override
                  public void cancel() {}
                });
    try {
      http.send(
          HttpRequest.newBuilder(api("/files/uploads/" + id + "/parts/" + n))
              .header("Authorization", bearer(user))
              .header("Idempotency-Key", id + "-" + n)
              .header("Content-Type", "application/octet-stream")
              .PUT(BodyPublishers.fromPublisher(half, len))
              .build(),
          BodyHandlers.ofString());
      return false;
    } catch (IOException | InterruptedException e) {
      return true;
    }
  }

  JsonNode complete(UUID user, String id, List<String> etags, int expect) throws Exception {
    List<Map<String, Object>> parts = new ArrayList<>();
    for (int i = 0; i < etags.size(); i++) {
      parts.add(Map.of("partNo", i + 1, "etag", etags.get(i)));
    }
    HttpResponse<String> r =
        send(
            HttpRequest.newBuilder(api("/files/uploads/" + id + "/complete"))
                .header("Authorization", bearer(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json.writeValueAsString(Map.of("parts", parts)))));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(expect);
    return json.readTree(r.body());
  }

  JsonNode getJson(UUID user, String path, int expect) throws Exception {
    HttpResponse<String> r =
        send(HttpRequest.newBuilder(api(path)).header("Authorization", bearer(user)).GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(expect);
    return json.readTree(r.body());
  }

  HttpResponse<String> post(UUID user, String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(api(path))
            .header("Authorization", bearer(user))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body)));
  }

  HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
    return http.send(b.build(), BodyHandlers.ofString());
  }

  URI api(String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  String bearer(UUID user) {
    return "Bearer " + user + "." + tenant;
  }

  void conforms(String path, PathItem.HttpMethod method, int status, JsonNode body) {
    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                contract, "/api/v1" + path, method, status, body.toString()))
        .isEmpty();
  }

  /** Gói DOCX tối thiểu kèm các mục thêm. */
  static byte[] docx(Map<String, String> extra) throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
      Map<String, String> entries = new java.util.LinkedHashMap<>();
      entries.put("[Content_Types].xml", "<Types/>");
      entries.put("word/document.xml", "<w:document/>");
      entries.putAll(extra);
      for (var e : entries.entrySet()) {
        zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.US_ASCII));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  static byte[] pdf(int size) {
    byte[] b = new byte[size];
    new Random(size).nextBytes(b);
    byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(head, 0, b, 0, Math.min(head.length, size));
    return b;
  }

  static String sha(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
}
