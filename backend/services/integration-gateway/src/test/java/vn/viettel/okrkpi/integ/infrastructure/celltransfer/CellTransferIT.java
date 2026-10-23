package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import vn.viettel.okrkpi.integ.IntegrationServiceApplication;
import vn.viettel.okrkpi.integ.application.CellTransferService;
import vn.viettel.okrkpi.integ.infrastructure.crypto.JcaPackageCrypto;
import vn.viettel.okrkpi.integ.infrastructure.web.InternalCellTransferController;
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
 * DV-13-10 với hai cell giả lập trong cùng Testcontainers: CELL-HN và CELL-HCM mỗi cell một CSDL
 * PostgreSQL, một bucket {@code transfer}, một cặp khoá ký EC P-256 và một cặp khoá nhận gói RSA
 * 3072; danh bạ khoá cell ký bằng khoá gốc. MOD-04, MOD-06, SVC-04 giả lập bằng một máy chủ HTTP
 * (YC-PT-13). Gói đi từ HN tới HCM đạt {@code DA_NHAP} ở cả hai phía; sửa một byte bản mã trong
 * bucket nguồn thì HCM {@code TU_CHOI} và HN biết lý do; khoá nội dung không có dạng rõ trong CSDL,
 * kho đối tượng hay nhật ký.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CellTransferIT {

  static final String PASSWORD = "mat-khau-thu-nghiem";
  static final UUID TENANT_HN = UUID.fromString("0192f1a2-0000-7000-8000-0000001310a1");
  static final UUID TENANT_HCM = UUID.fromString("0192f1a2-0000-7000-8000-0000001310b1");
  static final UUID PERSON = UUID.fromString("0192f1a2-0000-7000-8000-0000001310c1");
  static final UUID PERSON_2 = UUID.fromString("0192f1a2-0000-7000-8000-0000001310c2");
  static final UUID PACKAGE = UUID.fromString("0192f1a2-0000-7000-8000-0000001310d1");
  static final UUID PACKAGE_TAMPERED = UUID.fromString("0192f1a2-0000-7000-8000-0000001310d2");
  static final String RATINGS =
      "{\"ky\":\"2025\",\"xepLoai\":\"HTXS\",\"dauVet\":\"BAN-RO-XEP-LOAI\"}";
  static final String DOSSIER = "%PDF-1.7 ho so so da ky BAN-RO-HO-SO";
  static final ObjectMapper JSON = new ObjectMapper();

  static final PostgreSQLContainer<?> PG_HN = TestPostgres.container("mission_cell_hn");
  static final PostgreSQLContainer<?> PG_HCM = TestPostgres.container("mission_cell_hcm");
  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:latest")
          .withExposedPorts(9090)
          .waitingFor(Wait.forListeningPort());

  static final Map<String, byte[]> IMPORTED = new ConcurrentHashMap<>();
  static final List<String> IMPORT_KEYS = new CopyOnWriteArrayList<>();
  static HttpServer modules;
  static Path keys;
  static KeyPair rootKey;
  static KeyPair hnSign;
  static KeyPair hcmSign;
  static KeyPair hnBox;
  static KeyPair hcmBox;
  static int hnPort;
  static int hcmPort;
  static S3Client s3;
  static ConfigurableApplicationContext hn;
  static ConfigurableApplicationContext hcm;

  final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    PG_HN.start();
    PG_HCM.start();
    S3.start();
    migrate(PG_HN);
    migrate(PG_HCM);
    s3 =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .httpClient(UrlConnectionHttpClient.create())
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .forcePathStyle(true)
            .endpointOverride(URI.create(s3Endpoint()))
            .build();
    s3.createBucket(b -> b.bucket("transfer-hn").objectLockEnabledForBucket(true));
    s3.createBucket(b -> b.bucket("transfer-hcm").objectLockEnabledForBucket(true));
    modules = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    modules.createContext("/hn/", CellTransferIT::collect);
    modules.createContext("/hcm/import/", CellTransferIT::importPart);
    modules.start();
    keys = Files.createTempDirectory("cell-keys");
    rootKey = ec();
    hnSign = ec();
    hcmSign = ec();
    hnBox = rsa();
    hcmBox = rsa();
    hnPort = freePort();
    hcmPort = freePort();
    writeDirectory();
    hn = cell("HN", hnPort, PG_HN, hnSign, hnBox, "transfer-hn");
    hcm = cell("HCM", hcmPort, PG_HCM, hcmSign, hcmBox, "transfer-hcm");
  }

  @AfterAll
  static void stop() {
    if (hn != null) {
      hn.close();
    }
    if (hcm != null) {
      hcm.close();
    }
    if (modules != null) {
      modules.stop(0);
    }
    S3.stop();
  }

  // ------------------------------------------------------------------ hạ tầng giả lập

  static ConfigurableApplicationContext cell(
      String code, int port, PostgreSQLContainer<?> pg, KeyPair sign, KeyPair box, String bucket)
      throws IOException {
    Path signKey = pem(code + "-ky.pem", "PRIVATE KEY", sign.getPrivate().getEncoded());
    Path boxKey = pem(code + "-nhan.pem", "PRIVATE KEY", box.getPrivate().getEncoded());
    Path root = pem("goc.pem", "PUBLIC KEY", rootKey.getPublic().getEncoded());
    String parts = "okrkpi.integ.cell-transfer.parts";
    String moduleBase = "http://127.0.0.1:" + modules.getAddress().getPort();
    return new SpringApplicationBuilder(IntegrationServiceApplication.class, Fakes.class)
        .initializers(
            c ->
                c.getBeanFactory()
                    .registerSingleton("bỏQuaCauHinhKiemThu", new SkipOtherTestConfigs()))
        .run(
            "--server.port=" + port,
            "--management.server.port=0",
            "--spring.datasource.url=" + pg.getJdbcUrl() + "?currentSchema=integ",
            "--spring.datasource.username=svc_integ",
            "--spring.datasource.password=" + PASSWORD,
            "--okrkpi.opa.enabled=false",
            "--okrkpi.crypto.enabled=false",
            "--okrkpi.integ.auth-enabled=false",
            "--okrkpi.integ.voffice.enabled=false",
            "--okrkpi.integ.truc.enabled=false",
            "--okrkpi.integ.hrm.enabled=false",
            "--okrkpi.integ.lgsp.enabled=false",
            "--okrkpi.integ.sync-run.enabled=false",
            "--okrkpi.integ.outbound.dispatch.enabled=false",
            "--okrkpi.integ.outbound.consumer.enabled=false",
            "--okrkpi.integ.cell-transfer.enabled=true",
            "--okrkpi.integ.cell-transfer.dispatch.enabled=false",
            "--okrkpi.integ.cell-transfer.cell-code=" + code,
            "--okrkpi.integ.cell-transfer.signing-key-path=" + signKey,
            "--okrkpi.integ.cell-transfer.decryption-key-path=" + boxKey,
            "--okrkpi.integ.cell-transfer.directory.location=file:" + keys.resolve("danh-ba.json"),
            "--okrkpi.integ.cell-transfer.directory.root-key-path=" + root,
            "--okrkpi.integ.cell-transfer.directory.allow-http=true",
            "--okrkpi.integ.cell-transfer.storage.endpoint=" + s3Endpoint(),
            "--okrkpi.integ.cell-transfer.storage.bucket=" + bucket,
            "--okrkpi.integ.cell-transfer.storage.access-key=gia-lap",
            "--okrkpi.integ.cell-transfer.storage.secret-key=gia-lap",
            "--okrkpi.integ.cell-transfer.storage.retention=P1D",
            "--" + parts + "[KET_QUA_XEP_LOAI].name=ket-qua-xep-loai.json",
            "--" + parts + "[KET_QUA_XEP_LOAI].media-type=application/json",
            "--"
                + parts
                + "[KET_QUA_XEP_LOAI].collect-url="
                + moduleBase
                + "/hn/xep-loai/{personId}",
            "--" + parts + "[KET_QUA_XEP_LOAI].import-url=" + moduleBase + "/hcm/import/xep-loai",
            "--" + parts + "[HO_SO_SO].name=ho-so-so.pdf",
            "--" + parts + "[HO_SO_SO].media-type=application/pdf",
            "--" + parts + "[HO_SO_SO].collect-url=" + moduleBase + "/hn/ho-so-so/{personId}",
            "--" + parts + "[HO_SO_SO].import-url=" + moduleBase + "/hcm/import/ho-so-so",
            "--" + parts + "[NHAN_XET].name=nhan-xet.json",
            "--" + parts + "[NHAN_XET].media-type=application/json",
            "--" + parts + "[NHAN_XET].collect-url=" + moduleBase + "/hn/nhan-xet/{personId}",
            "--" + parts + "[NHAN_XET].import-url=" + moduleBase + "/hcm/import/nhan-xet");
  }

  /**
   * Bỏ các {@code @TestConfiguration} của IT khác mà quét thành phần sẽ nhặt (ngữ cảnh tự dựng).
   */
  static final class SkipOtherTestConfigs extends TypeExcludeFilter {
    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
      return reader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof SkipOtherTestConfigs;
    }

    @Override
    public int hashCode() {
      return SkipOtherTestConfigs.class.hashCode();
    }
  }

  /** Bộ tuần tự hoá outbox giả (không cần Apicurio). */
  static class Fakes {
    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  /** MOD-04, MOD-06 của HN: xếp loại và hồ sơ số; nhận xét không có (404). */
  static void collect(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    if (!TENANT_HN.toString().equals(ex.getRequestHeaders().getFirst("X-Tenant-Id"))) {
      reply(ex, 403, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    } else if (path.startsWith("/hn/xep-loai/")) {
      reply(ex, 200, "application/json", RATINGS.getBytes(StandardCharsets.UTF_8));
    } else if (path.startsWith("/hn/ho-so-so/")) {
      reply(ex, 200, "application/pdf", DOSSIER.getBytes(StandardCharsets.UTF_8));
    } else {
      reply(ex, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    }
  }

  /** SVC-04, MOD-06 của HCM: ghi lại phần nhập theo khoá chống trùng. */
  static void importPart(HttpExchange ex) throws IOException {
    if (!TENANT_HCM.toString().equals(ex.getRequestHeaders().getFirst("X-Tenant-Id"))) {
      reply(ex, 403, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
    IMPORT_KEYS.add(key);
    IMPORTED.put(key, ex.getRequestBody().readAllBytes());
    reply(ex, 201, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
  }

  static void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException {
    ex.getResponseHeaders().add("Content-Type", type);
    ex.sendResponseHeaders(code, body.length);
    ex.getResponseBody().write(body);
    ex.close();
  }

  static void writeDirectory() throws Exception {
    Base64.Encoder b64 = Base64.getEncoder();
    Instant now = Instant.now();
    var doc =
        JSON.createObjectNode()
            .put("version", 3)
            .put("expiresAt", now.plusSeconds(86400).toString());
    var cells = doc.putArray("cells");
    for (Object[] c :
        new Object[][] {{"HN", hnPort, hnSign, hnBox}, {"HCM", hcmPort, hcmSign, hcmBox}}) {
      cells
          .addObject()
          .put("cell", (String) c[0])
          .put("endpoint", "http://127.0.0.1:" + c[1])
          .put("signingKey", b64.encodeToString(((KeyPair) c[2]).getPublic().getEncoded()))
          .put("encryptionKey", b64.encodeToString(((KeyPair) c[3]).getPublic().getEncoded()))
          .put("notBefore", now.minusSeconds(3600).toString())
          .put("notAfter", now.plusSeconds(86400).toString());
    }
    byte[] payload = JSON.writeValueAsBytes(doc);
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(rootKey.getPrivate());
    s.update(payload);
    Files.write(
        keys.resolve("danh-ba.json"),
        JSON.writeValueAsBytes(
            Map.of(
                "payload",
                b64.encodeToString(payload),
                "signature",
                b64.encodeToString(s.sign()))));
  }

  static void migrate(PostgreSQLContainer<?> pg) {
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.CELL_SHARED)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.CELL_SHARED);
      new SchemaMigrator(MigrationOptions.defaults())
          .migrate(
              pg.getJdbcUrl(),
              "integ",
              new MigrationCredentials.Credentials("mig_integ", PASSWORD));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static KeyPair ec() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  static KeyPair rsa() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(3072);
    return g.generateKeyPair();
  }

  static Path pem(String name, String type, byte[] der) throws IOException {
    Path p = keys.resolve(name);
    Files.writeString(
        p,
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der)
            + "\n-----END "
            + type
            + "-----\n");
    return p;
  }

  static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  static String s3Endpoint() {
    return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
  }

  static JdbcTemplate db(PostgreSQLContainer<?> pg) {
    return new JdbcTemplate(
        new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
  }

  static Map<String, Object> row(PostgreSQLContainer<?> pg, UUID id) {
    return db(pg)
        .queryForMap(
            "SELECT direction, status, reason, peer_cell_code, peer_tenant_code, manifest::text AS"
                + " manifest, object_key, wrapped_key, peer_notified_at FROM integ.transfer_package"
                + " WHERE id = ?",
            id);
  }

  static List<String> audits(PostgreSQLContainer<?> pg) {
    return db(pg)
        .queryForList(
            "SELECT aggregate_id::text FROM integ.outbox_event WHERE topic = 'sys.audit.v1'"
                + " AND aggregate_type = 'TRANSFER_PACKAGE' ORDER BY created_at",
            String.class);
  }

  static CellTransferService service(ConfigurableApplicationContext c) {
    return c.getBean(CellTransferService.class);
  }

  HttpResponse<String> post(int port, String path, String tenant, String body) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (tenant != null) {
      b.header("X-Tenant-Id", tenant);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  String packRequest(UUID packageId, UUID person) {
    return """
        {"packageId":"%s","personId":"%s","sourceTenantCode":"HN-SO-NOI-VU","targetCell":"HCM",
         "targetTenantId":"%s","targetTenantCode":"HCM-SO-NOI-VU"}"""
        .formatted(packageId, person, TENANT_HCM);
  }

  // ------------------------------------------------------------------ kiểm thử

  @Test
  @Order(1)
  void packageTravelsFromHanoiToHcmAndIsImported() throws Exception {
    HttpResponse<String> r =
        post(
            hnPort,
            InternalCellTransferController.BASE,
            TENANT_HN.toString(),
            packRequest(PACKAGE, PERSON));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
    assertThat(JSON.readTree(r.body()).path("status").asText()).isEqualTo("DONG_GOI");
    // Gọi lại cùng mã gói không đóng gói lần hai.
    HttpResponse<String> again =
        post(
            hnPort,
            InternalCellTransferController.BASE,
            TENANT_HN.toString(),
            packRequest(PACKAGE, PERSON));
    assertThat(again.statusCode()).isEqualTo(202);
    assertThat(db(PG_HN).queryForObject("SELECT count(*) FROM integ.transfer_package", Long.class))
        .isEqualTo(1L);

    Map<String, Object> source = row(PG_HN, PACKAGE);
    JsonNode manifest = JSON.readTree((String) source.get("manifest"));
    assertThat(manifest.path("parts")).hasSize(2);
    assertThat(manifest.path("parts").get(0).path("sha256").asText()).hasSize(64);

    assertThat(service(hn).processDue(10)).isEqualTo(1);
    assertThat(row(PG_HN, PACKAGE).get("status")).isEqualTo("DA_GUI");
    Map<String, Object> received = row(PG_HCM, PACKAGE);
    assertThat(received.get("direction")).isEqualTo("DEN");
    assertThat(received.get("status")).isEqualTo("DA_NHAN");
    assertThat(received.get("peer_cell_code")).isEqualTo("HN");
    assertThat(received.get("peer_tenant_code")).isEqualTo("HN-SO-NOI-VU");

    assertThat(service(hcm).processDue(10)).isEqualTo(1);
    assertThat(row(PG_HCM, PACKAGE).get("status")).isEqualTo("DA_NHAP");
    assertThat(row(PG_HCM, PACKAGE).get("peer_notified_at")).isNotNull();
    Map<String, Object> done = row(PG_HN, PACKAGE);
    assertThat(done.get("status")).isEqualTo("DA_NHAP");
    assertThat(done.get("peer_notified_at")).isNotNull();

    assertThat(IMPORT_KEYS)
        .containsExactlyInAnyOrder(PACKAGE + ":ket-qua-xep-loai.json", PACKAGE + ":ho-so-so.pdf");
    assertThat(new String(IMPORTED.get(PACKAGE + ":ket-qua-xep-loai.json"), StandardCharsets.UTF_8))
        .isEqualTo(RATINGS);
    assertThat(new String(IMPORTED.get(PACKAGE + ":ho-so-so.pdf"), StandardCharsets.UTF_8))
        .isEqualTo(DOSSIER);

    // Sổ cái hai phía: HN đóng gói, gửi, nhận kết quả; HCM nhận, nhập.
    assertThat(audits(PG_HN)).hasSize(3).containsOnly(PACKAGE.toString());
    assertThat(audits(PG_HCM)).hasSize(2).containsOnly(PACKAGE.toString());
    // Worker không còn việc.
    assertThat(service(hn).processDue(10)).isZero();
    assertThat(service(hcm).processDue(10)).isZero();
  }

  @Test
  @Order(2)
  void oneTamperedByteMakesTheTargetRefuse() throws Exception {
    HttpResponse<String> r =
        post(
            hnPort,
            InternalCellTransferController.BASE,
            TENANT_HN.toString(),
            packRequest(PACKAGE_TAMPERED, PERSON_2));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
    String key = (String) row(PG_HN, PACKAGE_TAMPERED).get("object_key");
    byte[] object = s3.getObjectAsBytes(b -> b.bucket("transfer-hn").key(key)).asByteArray();
    object[object.length / 2] ^= 0x01;
    s3.putObject(b -> b.bucket("transfer-hn").key(key), RequestBody.fromBytes(object));
    int importsBefore = IMPORT_KEYS.size();

    assertThat(service(hn).processDue(10)).isEqualTo(1);

    Map<String, Object> target = row(PG_HCM, PACKAGE_TAMPERED);
    assertThat(target.get("status")).isEqualTo("TU_CHOI");
    assertThat(target.get("reason")).isEqualTo(CellTransferService.Reasons.BAD_DIGEST);
    Map<String, Object> source = row(PG_HN, PACKAGE_TAMPERED);
    assertThat(source.get("status")).isEqualTo("TU_CHOI");
    assertThat(source.get("reason")).isEqualTo(CellTransferService.Reasons.BAD_DIGEST);
    assertThat(service(hcm).processDue(10)).isZero();
    assertThat(IMPORT_KEYS).hasSize(importsBefore);
    assertThat(audits(PG_HCM)).contains(PACKAGE_TAMPERED.toString());
  }

  @Test
  @Order(3)
  void forgedSignatureAndForgedOutcomeAreRefused() throws Exception {
    Map<String, Object> genuine = row(PG_HCM, PACKAGE);
    byte[] header =
        db(PG_HCM)
            .queryForObject(
                "SELECT signed_header FROM integ.transfer_package WHERE id = ?",
                byte[].class,
                PACKAGE);
    KeyPair attacker = ec();
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(attacker.getPrivate());
    s.update(header);
    Base64.Encoder b64 = Base64.getEncoder();
    String body =
        JSON.writeValueAsString(
            Map.of(
                "header", b64.encodeToString(header),
                "signature", b64.encodeToString(s.sign()),
                "wrappedKey", b64.encodeToString((byte[]) genuine.get("wrapped_key")),
                "content", b64.encodeToString(new byte[] {1, 2, 3})));
    HttpResponse<String> r =
        post(hcmPort, InternalCellTransferController.BASE + "/inbound", null, body);
    assertThat(r.statusCode()).isEqualTo(422);
    assertThat(JSON.readTree(r.body()).path("reason").asText())
        .isEqualTo(CellTransferService.Reasons.BAD_SIGNATURE);
    // Gói thật đã nhập không bị gói giả cùng mã ghi đè.
    assertThat(row(PG_HCM, PACKAGE).get("status")).isEqualTo("DA_NHAP");

    byte[] outcome =
        ("{\"packageId\":\""
                + PACKAGE
                + "\",\"sourceTenantId\":\""
                + TENANT_HN
                + "\",\"cell\":\"HCM\",\"status\":\"TU_CHOI\",\"reason\":\"NHAP_LOI\"}")
            .getBytes(StandardCharsets.UTF_8);
    Signature o = Signature.getInstance("SHA256withECDSA");
    o.initSign(attacker.getPrivate());
    o.update(outcome);
    HttpResponse<String> forged =
        post(
            hnPort,
            InternalCellTransferController.BASE + "/outcomes",
            null,
            JSON.writeValueAsString(
                Map.of(
                    "payload", b64.encodeToString(outcome),
                    "signature", b64.encodeToString(o.sign()))));
    assertThat(forged.statusCode()).isEqualTo(422);
    assertThat(row(PG_HN, PACKAGE).get("status")).isEqualTo("DA_NHAP");
  }

  @Test
  @Order(4)
  void contentKeyNeverAppearsInClear(CapturedOutput output) throws Exception {
    byte[] wrapped = (byte[]) row(PG_HCM, PACKAGE).get("wrapped_key");
    Cipher unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding");
    unwrap.init(Cipher.DECRYPT_MODE, hcmBox.getPrivate(), JcaPackageCrypto.OAEP_SHA256);
    byte[] cek = unwrap.doFinal(wrapped);
    assertThat(cek).hasSize(32);
    String hex = HexFormat.of().formatHex(cek);
    String b64 = Base64.getEncoder().encodeToString(cek);

    for (PostgreSQLContainer<?> pg : List.of(PG_HN, PG_HCM)) {
      String dump =
          String.join(
                  "\n",
                  db(pg).queryForList("SELECT t::text FROM integ.transfer_package t", String.class))
              + String.join(
                  "\n",
                  db(pg).queryForList("SELECT o::text FROM integ.outbox_event o", String.class));
      assertThat(dump).doesNotContain(hex).doesNotContain(b64).doesNotContain("BAN-RO-");
    }

    List<byte[]> objects = new ArrayList<>();
    for (String bucket : List.of("transfer-hn", "transfer-hcm")) {
      for (S3Object o : s3.listObjectsV2(b -> b.bucket(bucket)).contents()) {
        objects.add(s3.getObjectAsBytes(b -> b.bucket(bucket).key(o.key())).asByteArray());
      }
    }
    assertThat(objects).hasSizeGreaterThanOrEqualTo(3);
    for (byte[] o : objects) {
      String text = new String(o, StandardCharsets.ISO_8859_1);
      assertThat(text).doesNotContain("BAN-RO-").doesNotContain("PK\u0003\u0004");
      assertThat(HexFormat.of().formatHex(o)).doesNotContain(hex);
    }

    assertThat(output.getAll()).doesNotContain(hex).doesNotContain(b64).doesNotContain("BAN-RO-");
  }
}
