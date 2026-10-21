package vn.viettel.okrkpi.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.testdb.TenantDatabase;

/**
 * Kiểm thử tích hợp {@code FieldEncryptor}, {@code DekService}, {@code DekCache}, {@code
 * BlindIndexer} và {@code OpenBaoTransitClient} với PostgreSQL 17 ({@code keyring.dek} có RLS FORCE
 * của NEN-032) và OpenBao 2.1.0 chế độ dev có Transit engine (NEN-015).
 */
class FieldEncryptionIT {

  private static final String DEV_ROOT_TOKEN = "dev-openbao-root-token";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HexFormat HEX = HexFormat.of();

  private static GenericContainer<?> openBao;
  private static Path tokenFile;
  private static URI openBaoUri;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    TenantDatabase.start();

    openBao =
        new GenericContainer<>(DockerImageName.parse("quay.io/openbao/openbao:2.1.0"))
            .withEnv("BAO_DEV_ROOT_TOKEN_ID", DEV_ROOT_TOKEN)
            .withEnv("BAO_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
            .withCommand("server", "-dev")
            .withExposedPorts(8200)
            .waitingFor(
                Wait.forHttp("/v1/sys/health")
                    .forPort(8200)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));
    openBao.start();

    openBaoUri = URI.create("http://" + openBao.getHost() + ":" + openBao.getMappedPort(8200));
    enableTransitEngine(openBaoUri);

    tokenFile = Files.createTempFile("openbao-token-", ".txt");
    Files.writeString(tokenFile, DEV_ROOT_TOKEN, StandardCharsets.UTF_8);
  }

  @AfterAll
  static void stopInfrastructure() throws Exception {
    if (tokenFile != null) {
      Files.deleteIfExists(tokenFile);
    }
    if (openBao != null) {
      openBao.stop();
    }
  }

  @Test
  void vongMaHoaGiaiMaDoiThangXoayDekVaHuyKhoaBangMatMa() throws Exception {
    ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
    OutputStreamAppender appender = attachLogCapture("nen015-capture", logBuffer);
    String sensitivePlaintext = "Bản tự kiểm điểm C2 tuyệt mật: Cần khắc phục hạn chế mục 3.2";

    try {
      UUID tenantId = UUID.fromString("01950000-0000-7000-8000-000000000101");
      Clock octClock = Clock.fixed(Instant.parse("2026-10-10T09:00:00Z"), ZoneOffset.UTC);
      Clock novClock = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC);

      JdbcTemplate svcTaskJdbc = new JdbcTemplate(TenantDatabase.dataSource("svc_task"));
      CryptoProvider provider = new JcaAesGcmCryptoProvider();
      OpenBaoTransitClient transit = new OpenBaoTransitClient(buildProps(), MAPPER, octClock);
      DekCache cache = new DekCache(1_000, Duration.ofMinutes(15), octClock);
      DekService dekService = new DekService(svcTaskJdbc, transit, provider, cache, octClock);
      FieldEncryptor encryptor = new FieldEncryptor(dekService, provider);
      BlindIndexer blindIndexer = new BlindIndexer(dekService, provider);

      List<String> aadParts =
          List.of("appraisal.self_review.comment_enc", "01950000-0000-7000-8000-000000000999");

      // 1. Mã hoá ở tháng 10/2026 -> tự sinh DEK tháng 10 trong keyring.dek qua OpenBao Transit
      EncryptedField octEncrypted =
          encryptor.encryptField(
              tenantId,
              DekPurpose.GOP_Y_KIEM_DIEM,
              aadParts,
              sensitivePlaintext.getBytes(StandardCharsets.UTF_8));
      UUID octDekId = octEncrypted.dekId();

      // Xoá cache để bắt buộc lần giải mã đọc từ keyring.dek và mở bọc qua OpenBao Transit
      cache.clear();
      String decryptedOct =
          encryptor.decryptToString(
              tenantId, DekPurpose.GOP_Y_KIEM_DIEM, aadParts, octEncrypted.ciphertext());
      assertThat(decryptedOct).isEqualTo(sensitivePlaintext);

      // 2. Sang tháng 11/2026 -> sinh DEK mới cho tháng 11
      dekService.setClock(novClock);
      EncryptedField novEncrypted =
          encryptor.encryptField(
              tenantId,
              DekPurpose.GOP_Y_KIEM_DIEM,
              aadParts,
              "Nội dung tháng 11".getBytes(StandardCharsets.UTF_8));
      UUID novDekId = novEncrypted.dekId();
      assertThat(novDekId).isNotEqualTo(octDekId);

      // Chuyển DEK tháng 10 sang DECRYPT_ONLY bằng vai trò svc_keyring (mô phỏng job xoay vòng)
      updateDekStatusAsKeyringRole(tenantId, octDekId, "DECRYPT_ONLY", false);
      cache.clear();

      // Bản mã tháng 10 (DECRYPT_ONLY) và bản mã tháng 11 (ACTIVE) đều giải mã được
      assertThat(
              encryptor.decryptToString(
                  tenantId, DekPurpose.GOP_Y_KIEM_DIEM, aadParts, octEncrypted.ciphertext()))
          .isEqualTo(sensitivePlaintext);
      assertThat(
              encryptor.decryptToString(
                  tenantId, DekPurpose.GOP_Y_KIEM_DIEM, aadParts, novEncrypted.ciphertext()))
          .isEqualTo("Nội dung tháng 11");
      CachedDek cachedBeforeDestroy = cache.getById(tenantId, octDekId).orElseThrow();
      String rawKeyHex = HEX.formatHex(cachedBeforeDestroy.keyBytes());

      // 3. Chỉ mục mù ổn định giữa tháng 10 và tháng 11
      byte[] bidx1 = blindIndexer.compute(tenantId, "identity.user.cccd", " 001200012345 ");
      cache.clear();
      byte[] bidx2 = blindIndexer.compute(tenantId, "identity.user.cccd", "001200012345");
      assertThat(bidx1).hasSize(16).isEqualTo(bidx2);

      // 4. Huỷ DEK tháng 10 (DESTROYED - xoá bằng mật mã) -> giải mã phải báo DekDestroyedException
      updateDekStatusAsKeyringRole(tenantId, octDekId, "DESTROYED", true);
      cache.invalidate(tenantId, octDekId);

      assertThatThrownBy(
              () ->
                  encryptor.decryptToString(
                      tenantId, DekPurpose.GOP_Y_KIEM_DIEM, aadParts, octEncrypted.ciphertext()))
          .isInstanceOf(DekDestroyedException.class)
          .hasMessageContaining(octDekId.toString());

      // 5. Quét nhật ký: không có bản rõ C2 hay khoá DEK dạng hex trong log
      String capturedLogs = logBuffer.toString(StandardCharsets.UTF_8);
      assertThat(capturedLogs)
          .doesNotContain(sensitivePlaintext)
          .doesNotContain("001200012345")
          .doesNotContain(rawKeyHex);
    } finally {
      detachLogCapture(appender);
    }
  }

  @Test
  void haiPodSinhDekDongThoiChiTaoMotDongTrongKeyringDek() throws Exception {
    UUID tenantId = UUID.fromString("01950000-0000-7000-8000-000000000202");
    LocalDate month = LocalDate.of(2026, 10, 1);
    Clock fixedClock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC);

    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    OpenBaoTransitClient transit = new OpenBaoTransitClient(buildProps(), MAPPER, fixedClock);
    // Khởi tạo trước KEK trên OpenBao để hai pod đua đúng vào bước sinh & chèn DEK
    transit.ensureKek(tenantId);

    JdbcTemplate pod1Jdbc = new JdbcTemplate(TenantDatabase.dataSource("svc_task"));
    JdbcTemplate pod2Jdbc = new JdbcTemplate(TenantDatabase.dataSource("svc_task"));

    DekService pod1DekService =
        new DekService(pod1Jdbc, transit, provider, new DekCache(), fixedClock);
    DekService pod2DekService =
        new DekService(pod2Jdbc, transit, provider, new DekCache(), fixedClock);

    FieldEncryptor pod1Encryptor = new FieldEncryptor(pod1DekService, provider);
    FieldEncryptor pod2Encryptor = new FieldEncryptor(pod2DekService, provider);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    List<String> aad1 = List.of("appraisal.complaint.content_enc", "row-pod-1");
    List<String> aad2 = List.of("appraisal.complaint.content_enc", "row-pod-2");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<EncryptedField> task1 =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Quá hạn chờ đua");
            }
            return pod1Encryptor.encryptField(
                tenantId,
                DekPurpose.KHIEU_NAI,
                aad1,
                "Khiếu nại từ pod 1".getBytes(StandardCharsets.UTF_8));
          };
      Callable<EncryptedField> task2 =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Quá hạn chờ đua");
            }
            return pod2Encryptor.encryptField(
                tenantId,
                DekPurpose.KHIEU_NAI,
                aad2,
                "Khiếu nại từ pod 2".getBytes(StandardCharsets.UTF_8));
          };

      Future<EncryptedField> f1 = pool.submit(task1);
      Future<EncryptedField> f2 = pool.submit(task2);

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      EncryptedField ef1 = f1.get(15, TimeUnit.SECONDS);
      EncryptedField ef2 = f2.get(15, TimeUnit.SECONDS);

      // Cả hai pod đều hội tụ về cùng một dek_id duy nhất trong keyring.dek
      assertThat(ef1.dekId()).isEqualTo(ef2.dekId());

      // Pod 2 giải mã được bản mã của Pod 1 và ngược lại
      assertThat(
              pod2Encryptor.decryptToString(tenantId, DekPurpose.KHIEU_NAI, aad1, ef1.ciphertext()))
          .isEqualTo("Khiếu nại từ pod 1");
      assertThat(
              pod1Encryptor.decryptToString(tenantId, DekPurpose.KHIEU_NAI, aad2, ef2.ciphertext()))
          .isEqualTo("Khiếu nại từ pod 2");

      // Kiểm chứng trong CSDL chỉ có đúng 1 dòng cho (tenantId, KHIEU_NAI, 2026-10-01)
      int count = countDekRows(tenantId, DekPurpose.KHIEU_NAI, month);
      assertThat(count).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private static CryptoProperties buildProps() {
    CryptoProperties props = new CryptoProperties();
    props.setOpenBaoBaseUrl(openBaoUri);
    props.setTokenFile(tokenFile);
    props.setAutoCreateKek(true);
    return props;
  }

  private static void enableTransitEngine(URI baseUri) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(baseUri.resolve("/v1/sys/mounts/transit"))
            .header("X-Vault-Token", DEV_ROOT_TOKEN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"transit\"}"))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 && response.statusCode() != 204) {
      throw new IllegalStateException(
          "Không bật được Transit engine trên OpenBao: HTTP "
              + response.statusCode()
              + " - "
              + response.body());
    }
  }

  private static void updateDekStatusAsKeyringRole(
      UUID tenantId, UUID dekId, String status, boolean destroyed) throws Exception {
    try (Connection conn = TenantDatabase.connect("svc_keyring")) {
      conn.setAutoCommit(false);
      try (PreparedStatement setTenant =
          conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        setTenant.setString(1, tenantId.toString());
        setTenant.executeQuery().close();
      }
      String sql =
          destroyed
              ? "UPDATE keyring.dek SET status = ?, destroyed_at = now() WHERE tenant_id = ? AND id = ?"
              : "UPDATE keyring.dek SET status = ?, destroyed_at = NULL WHERE tenant_id = ? AND id = ?";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, status);
        ps.setObject(2, tenantId);
        ps.setObject(3, dekId);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      conn.commit();
    }
  }

  private static int countDekRows(UUID tenantId, DekPurpose purpose, LocalDate validMonth)
      throws Exception {
    try (Connection conn = TenantDatabase.connect("svc_task")) {
      conn.setAutoCommit(false);
      try (PreparedStatement setTenant =
          conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        setTenant.setString(1, tenantId.toString());
        setTenant.executeQuery().close();
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT count(*) FROM keyring.dek WHERE tenant_id = ? AND purpose = ? AND valid_month = ?")) {
        ps.setObject(1, tenantId);
        ps.setString(2, purpose.name());
        ps.setDate(3, java.sql.Date.valueOf(validMonth));
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      } finally {
        conn.commit();
      }
    }
  }

  private static OutputStreamAppender attachLogCapture(String name, ByteArrayOutputStream out) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    PatternLayout layout = PatternLayout.createDefaultLayout(config);
    OutputStreamAppender appender =
        OutputStreamAppender.createAppender(layout, null, out, name, false, true);
    appender.start();
    config.addAppender(appender);
    config.getRootLogger().addAppender(appender, null, null);
    ctx.updateLoggers();
    return appender;
  }

  private static void detachLogCapture(OutputStreamAppender appender) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    config.getRootLogger().removeAppender(appender.getName());
    appender.stop();
    ctx.updateLoggers();
  }
}
