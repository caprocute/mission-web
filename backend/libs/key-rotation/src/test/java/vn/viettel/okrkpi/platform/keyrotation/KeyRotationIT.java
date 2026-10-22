package vn.viettel.okrkpi.platform.keyrotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.crypto.CryptoProperties;
import vn.viettel.okrkpi.shared.crypto.DekCache;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.OpenBaoTransitClient;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * DL-14: ba job xoay vòng trên PostgreSQL 17 (keyring, task migrate bằng mig_, RLS FORCE, kết nối
 * bằng svc_keyring và svc_task) và OpenBao 2.1.0 chế độ dev có Transit. Tên KEK dùng tiền tố của
 * INF-13 ({@code kek-tenant-}) để kiểm job chỉ đọc tên khoá từ {@code kek_ref}.
 */
class KeyRotationIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final String ROOT_TOKEN = "dev-openbao-root-token";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Clock OCT =
      Clock.fixed(Instant.parse("2026-10-10T09:00:00Z"), ZoneOffset.UTC);
  private static final Clock NOV =
      Clock.fixed(Instant.parse("2026-11-01T00:05:00Z"), ZoneOffset.UTC);
  private static final ActorId JOB =
      ActorId.of(UUID.fromString("01950000-0000-7000-8000-00000000a001"));

  private static PostgreSQLContainer<?> pg;
  private static GenericContainer<?> bao;
  private static URI baoUri;
  private static Path tokenFile;
  private static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
  private static final SimpleMeterRegistry METRICS = new SimpleMeterRegistry();
  private static final RotationReporter REPORTER = new RotationReporter(METRICS, AUDIT::add, JOB);

  /** Mọi DEK rõ đã thấy trong kiểm thử, để chứng minh không lọt vào nhật ký, chỉ số, sự kiện. */
  private static final List<byte[]> RAW_KEYS = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    pg = TestPostgres.container("td_key_rotation");
    pg.start();
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    }
    SchemaMigrator migrator = new SchemaMigrator(MigrationOptions.defaults());
    for (String schema : List.of("keyring", "task")) {
      migrator.migrate(
          pg.getJdbcUrl(),
          schema,
          new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), PASSWORD));
    }
    // Bảng nghiệp vụ thử có cột phong bì C2, tạo bằng mig_task như migration thật.
    try (Connection mig = DriverManager.getConnection(pg.getJdbcUrl(), "mig_task", PASSWORD);
        Statement st = mig.createStatement()) {
      st.execute(
          "CREATE TABLE task.thu_xoay_khoa (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,"
              + " ghi_chu_enc bytea, dek_id uuid, row_version int NOT NULL DEFAULT 0)");
      st.execute("COMMENT ON COLUMN task.thu_xoay_khoa.ghi_chu_enc IS '{\"cls\":\"C2\"}'");
      st.execute("ALTER TABLE task.thu_xoay_khoa ENABLE ROW LEVEL SECURITY");
      st.execute("ALTER TABLE task.thu_xoay_khoa FORCE ROW LEVEL SECURITY");
      st.execute(
          "CREATE POLICY rls_thu ON task.thu_xoay_khoa FOR ALL TO svc_task"
              + " USING (tenant_id = current_setting('app.tenant_id', true)::uuid)"
              + " WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid)");
    }

    bao =
        new GenericContainer<>(DockerImageName.parse("quay.io/openbao/openbao:2.1.0"))
            .withEnv("BAO_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
            .withEnv("BAO_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
            .withCommand("server", "-dev")
            .withExposedPorts(8200)
            .waitingFor(
                Wait.forHttp("/v1/sys/health")
                    .forPort(8200)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));
    bao.start();
    baoUri = URI.create("http://" + bao.getHost() + ":" + bao.getMappedPort(8200));
    baoPost("/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
    tokenFile = Files.createTempFile("openbao-token-", ".txt");
    Files.writeString(tokenFile, ROOT_TOKEN, StandardCharsets.UTF_8);
  }

  @AfterAll
  static void stop() throws Exception {
    if (tokenFile != null) {
      Files.deleteIfExists(tokenFile);
    }
    if (bao != null) {
      bao.stop();
    }
    if (pg != null) {
      pg.stop();
    }
  }

  @Test
  void dauThangSinhDekMoiDekCuChiGiaiMaVaChayLaiKhongDoi() {
    UUID tenant = UUID.fromString("01950000-0000-7000-8000-0000000000c1");
    JdbcTemplate keyring = jdbc("svc_keyring");
    DekService octDeks = dekService(keyring, OCT);
    FieldEncryptor oct = new FieldEncryptor(octDeks, new JcaAesGcmCryptoProvider());
    List<String> aad = List.of("appraisal.self_review.comment_enc", UUID.randomUUID().toString());
    EncryptedField nx =
        oct.encryptField(
            tenant,
            DekPurpose.NHAN_XET_DANH_GIA,
            aad,
            "Nhận xét tháng 10".getBytes(StandardCharsets.UTF_8));
    oct.encryptField(
        tenant, DekPurpose.KHIEU_NAI, aad, "Khiếu nại".getBytes(StandardCharsets.UTF_8));
    UUID bidx = octDeks.getOrCreateBlindIndexKey(tenant).dekId();
    remember(octDeks, tenant, nx.dekId());

    DekService novDeks = dekService(keyring, NOV);
    MonthlyDekRotationJob job =
        new MonthlyDekRotationJob(keyring, novDeks, () -> List.of(tenant), REPORTER, NOV);
    MonthlyDekRotationJob.Result r1 = job.run();
    assertThat(r1.created()).isEqualTo(2);
    assertThat(r1.demoted()).isEqualTo(2);
    assertThat(statuses(tenant)).containsEntry(bidx, "ACTIVE");
    assertThat(statusOf(tenant, nx.dekId())).isEqualTo("DECRYPT_ONLY");
    assertThat(countByMonth(tenant, LocalDate.of(2026, 11, 1), "ACTIVE")).isEqualTo(2);

    // Bản mã của DEK tháng trước (DECRYPT_ONLY) vẫn giải mã được bằng dịch vụ mới (bộ đệm trống).
    FieldEncryptor nov =
        new FieldEncryptor(dekService(keyring, NOV), new JcaAesGcmCryptoProvider());
    assertThat(nov.decryptToString(tenant, aad, nx.ciphertext())).isEqualTo("Nhận xét tháng 10");
    // Dữ liệu ghi mới dùng DEK tháng 11.
    assertThat(nov.encryptField(tenant, DekPurpose.NHAN_XET_DANH_GIA, aad, new byte[] {1}).dekId())
        .isNotEqualTo(nx.dekId());

    Map<UUID, String> before = statuses(tenant);
    MonthlyDekRotationJob.Result r2 = job.run();
    assertThat(r2.created()).isZero();
    assertThat(r2.demoted()).isZero();
    assertThat(statuses(tenant)).isEqualTo(before);
    assertThat(METRICS.find(RotationReporter.AGE).tag("muc_dich", "NHAN_XET_DANH_GIA").gauge())
        .isNotNull();
  }

  @Test
  void xoayKekRoiBocLai300DekDocLaiDuocVaChayLaiKhongDoi() throws Exception {
    UUID tenant = UUID.fromString("01950000-0000-7000-8000-0000000000c2");
    JdbcTemplate keyring = jdbc("svc_keyring");
    DekService deks = dekService(keyring, OCT);
    List<DekPurpose> purposes =
        List.of(
            DekPurpose.NHAN_XET_DANH_GIA,
            DekPurpose.KHIEU_NAI,
            DekPurpose.GOP_Y_KIEM_DIEM,
            DekPurpose.DINH_DANH_CA_NHAN,
            DekPurpose.TEP_MINH_CHUNG);
    for (DekPurpose p : purposes) {
      for (int m = 0; m < 60; m++) {
        deks.getOrCreateActiveDek(tenant, p, LocalDate.of(2022, 1, 1).plusMonths(m));
      }
    }
    assertThat(count(tenant)).isEqualTo(300);
    // Mẫu bản mã ở năm tháng khác nhau.
    FieldEncryptor enc = new FieldEncryptor(deks, new JcaAesGcmCryptoProvider());
    Map<byte[], String> samples = new LinkedHashMap<>();
    List<String> aad = List.of("task.thu_xoay_khoa.ghi_chu_enc", "mau");
    for (int m = 0; m < 60; m += 12) {
      Instant at = LocalDate.of(2022, 1, 15).plusMonths(m).atStartOfDay().toInstant(ZoneOffset.UTC);
      deks.setClock(Clock.fixed(at, ZoneOffset.UTC));
      String plain = "Mẫu tháng " + m;
      EncryptedField f =
          enc.encryptField(
              tenant, DekPurpose.KHIEU_NAI, aad, plain.getBytes(StandardCharsets.UTF_8));
      samples.put(f.ciphertext(), plain);
      remember(deks, tenant, f.dekId());
    }
    String kek = "kek-tenant-" + tenant;
    assertThat(kekRefs(tenant)).containsOnly("transit/keys/" + kek);
    baoPost("/v1/transit/keys/" + kek + "/rotate", "{}");

    KekRewrapJob job =
        new KekRewrapJob(
            keyring,
            new TransitRewrapClient(props(), MAPPER),
            () -> List.of(tenant),
            REPORTER,
            100);
    KekRewrapJob.Result r1 = job.run();
    assertThat(r1.rewrapped()).isEqualTo(300);
    assertThat(kekRefs(tenant)).containsOnly("transit/keys/" + kek + "@v2");
    assertThat(wrappedPrefixes(tenant)).containsOnly("vault:v2:");

    // Sau khi bọc lại, cấm giải bằng KEK v1 mà bản mã cũ vẫn đọc được.
    baoPost("/v1/transit/keys/" + kek + "/config", "{\"min_decryption_version\":2}");
    FieldEncryptor fresh =
        new FieldEncryptor(dekService(keyring, OCT), new JcaAesGcmCryptoProvider());
    samples.forEach(
        (ct, plain) -> assertThat(fresh.decryptToString(tenant, aad, ct)).isEqualTo(plain));

    KekRewrapJob.Result r2 = job.run();
    assertThat(r2.rewrapped()).isZero();
    assertThat(r2.upToDate()).isEqualTo(300);
  }

  @Test
  void maHoaLaiDekNghiLoTheoLoDungVaTiepTucRoiNghiHuu() throws Exception {
    UUID tenant = UUID.fromString("01950000-0000-7000-8000-0000000000c3");
    JdbcTemplate keyring = jdbc("svc_keyring");
    JdbcTemplate data = jdbc("svc_task");
    DekService octDeks = dekService(keyring, OCT);
    FieldEncryptor oct = new FieldEncryptor(octDeks, new JcaAesGcmCryptoProvider());
    Map<UUID, String> plain = new LinkedHashMap<>();
    UUID octDek = null;
    for (int i = 0; i < 250; i++) {
      UUID id = UUID.randomUUID();
      String v = "Góp ý kiểm điểm số " + i;
      EncryptedField f =
          oct.encryptField(
              tenant,
              DekPurpose.GOP_Y_KIEM_DIEM,
              List.of("task.thu_xoay_khoa.ghi_chu_enc", id.toString()),
              v.getBytes(StandardCharsets.UTF_8));
      octDek = f.dekId();
      insert(tenant, id, f.ciphertext(), f.dekId());
      plain.put(id, v);
    }
    insert(tenant, UUID.randomUUID(), null, octDek);
    insert(tenant, UUID.randomUUID(), null, octDek);
    remember(octDeks, tenant, octDek);
    new MonthlyDekRotationJob(
            keyring, dekService(keyring, NOV), () -> List.of(tenant), REPORTER, NOV)
        .run();
    assertThat(statusOf(tenant, octDek)).isEqualTo("DECRYPT_ONLY");

    assertThatThrownBy(() -> new Approval(JOB, JOB, "SC-01"))
        .isInstanceOf(IllegalArgumentException.class);
    Approval approval =
        new Approval(
            ActorId.of(UUID.fromString("01950000-0000-7000-8000-00000000b001")),
            ActorId.of(UUID.fromString("01950000-0000-7000-8000-00000000b002")),
            "SC-2026-11-01");

    DekService novDeks = dekService(keyring, NOV);
    DekReencryptJob job =
        new DekReencryptJob(
            keyring,
            data,
            new FieldEncryptor(novDeks, new JcaAesGcmCryptoProvider()),
            novDeks,
            REPORTER,
            NOV,
            Map.of(),
            Map.of(),
            100);
    UUID novDek = novDeks.getOrCreateActiveDek(tenant, DekPurpose.GOP_Y_KIEM_DIEM).dekId();
    remember(novDeks, tenant, novDek);
    assertThatThrownBy(() -> job.run(tenant, novDek, approval, () -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tháng hiện hành");

    AtomicInteger asked = new AtomicInteger();
    final UUID oldDek = octDek;
    DekReencryptJob.Result partial =
        job.run(tenant, oldDek, approval, () -> asked.incrementAndGet() > 1);
    assertThat(partial.rows()).isEqualTo(100);
    assertThat(partial.remaining()).isEqualTo(152);
    assertThat(partial.retired()).isFalse();
    assertThat(statusOf(tenant, oldDek)).isEqualTo("DECRYPT_ONLY");

    DekReencryptJob.Result rest = job.run(tenant, oldDek, approval, () -> false);
    assertThat(rest.rows()).isEqualTo(152);
    assertThat(rest.remaining()).isZero();
    assertThat(rest.retired()).isTrue();
    assertThat(statusOf(tenant, oldDek)).isEqualTo("RETIRED");

    FieldEncryptor reader =
        new FieldEncryptor(dekService(keyring, NOV), new JcaAesGcmCryptoProvider());
    int checked = 0;
    for (Object[] row : rows(tenant)) {
      UUID id = (UUID) row[0];
      assertThat(row[2]).isEqualTo(novDek);
      assertThat((Integer) row[3]).isEqualTo(1);
      if (row[1] != null) {
        assertThat(
                reader.decryptToString(
                    tenant,
                    List.of("task.thu_xoay_khoa.ghi_chu_enc", id.toString()),
                    (byte[]) row[1]))
            .isEqualTo(plain.get(id));
        assertThat(FieldEncryptor.extractDekId((byte[]) row[1])).isEqualTo(novDek);
        checked++;
      }
    }
    assertThat(checked).isEqualTo(250);
    AuditEvent last = AUDIT.get(AUDIT.size() - 1);
    assertThat(last.action()).isEqualTo("KEY_ROTATION_DEK_REENCRYPT");
    assertThat(last.actorId()).isEqualTo(approval.requestedBy());
    assertThat(last.attributes())
        .containsEntry("phieu", "SC-2026-11-01")
        .containsEntry("con_lai", "0");
  }

  @Test
  void khongCoDekRoTrongNhatKyChiSoVaSuKien() throws Exception {
    ByteArrayOutputStream logs = new ByteArrayOutputStream();
    OutputStreamAppender appender = attachLogCapture(logs);
    try {
      // Chạy lại cả ba loại job để chắc nhật ký của lần chạy nằm trong bộ bắt.
      UUID tenant = UUID.fromString("01950000-0000-7000-8000-0000000000c4");
      JdbcTemplate keyring = jdbc("svc_keyring");
      DekService octDeks = dekService(keyring, OCT);
      UUID d = octDeks.getOrCreateActiveDek(tenant, DekPurpose.KHIEU_NAI).dekId();
      remember(octDeks, tenant, d);
      new MonthlyDekRotationJob(
              keyring, dekService(keyring, NOV), () -> List.of(tenant), REPORTER, NOV)
          .run(YearMonth.of(2026, 11));
      baoPost("/v1/transit/keys/kek-tenant-" + tenant + "/rotate", "{}");
      new KekRewrapJob(
              keyring,
              new TransitRewrapClient(props(), MAPPER),
              () -> List.of(tenant),
              REPORTER,
              50)
          .run();
    } finally {
      detachLogCapture(appender);
    }
    String text =
        logs.toString(StandardCharsets.UTF_8)
            + "\n"
            + KeyRotationMain.prometheusText(METRICS)
            + "\n"
            + AUDIT;
    assertThat(RAW_KEYS).isNotEmpty();
    for (byte[] k : RAW_KEYS) {
      assertThat(text)
          .doesNotContain(HexFormat.of().formatHex(k))
          .doesNotContain(Base64.getEncoder().encodeToString(k));
    }
    assertThat(text).doesNotContain("Góp ý kiểm điểm số").doesNotContain(ROOT_TOKEN);
  }

  // ------------------------------------------------------------------ tiện ích

  private static CryptoProperties props() {
    CryptoProperties p = new CryptoProperties();
    p.setOpenBaoBaseUrl(baoUri);
    p.setTokenFile(tokenFile);
    p.setKekPrefix("kek-tenant-");
    p.setAutoCreateKek(true);
    return p;
  }

  private static DekService dekService(JdbcTemplate jdbc, Clock clock) {
    return new DekService(
        jdbc,
        new OpenBaoTransitClient(props(), MAPPER, clock),
        new JcaAesGcmCryptoProvider(),
        new DekCache(1_000, Duration.ofMinutes(15), clock),
        clock);
  }

  private static void remember(DekService deks, UUID tenant, UUID dek) {
    RAW_KEYS.add(deks.getDekForDecryption(tenant, dek).keyBytes());
  }

  private static JdbcTemplate jdbc(String role) {
    return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), role, PASSWORD));
  }

  private static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static Map<UUID, String> statuses(UUID tenant) throws RuntimeException {
    Map<UUID, String> out = new LinkedHashMap<>();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT id, status FROM keyring.dek WHERE tenant_id = ? ORDER BY id")) {
      ps.setObject(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.put(rs.getObject(1, UUID.class), rs.getString(2));
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static String statusOf(UUID tenant, UUID dek) {
    return statuses(tenant).get(dek);
  }

  private static List<String> query(String sql, UUID tenant) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = admin();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static int count(UUID tenant) throws Exception {
    return Integer.parseInt(
        query("SELECT count(*) FROM keyring.dek WHERE tenant_id = ?", tenant).get(0));
  }

  private static int countByMonth(UUID tenant, LocalDate month, String status) {
    try {
      return (int)
          query("SELECT status || '|' || valid_month FROM keyring.dek WHERE tenant_id = ?", tenant)
              .stream()
              .filter(s -> s.equals(status + "|" + month))
              .count();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> kekRefs(UUID tenant) throws Exception {
    return query("SELECT DISTINCT kek_ref FROM keyring.dek WHERE tenant_id = ?", tenant);
  }

  private static List<String> wrappedPrefixes(UUID tenant) throws Exception {
    return query(
        "SELECT DISTINCT substring(convert_from(wrapped_dek, 'UTF8') from '^vault:v[0-9]+:') FROM keyring.dek WHERE tenant_id = ?",
        tenant);
  }

  private static void insert(UUID tenant, UUID id, byte[] enc, UUID dek) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO task.thu_xoay_khoa (id, tenant_id, ghi_chu_enc, dek_id) VALUES (?,?,?,?)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setBytes(3, enc);
      ps.setObject(4, dek);
      ps.executeUpdate();
    }
  }

  private static List<Object[]> rows(UUID tenant) throws Exception {
    List<Object[]> out = new ArrayList<>();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT id, ghi_chu_enc, dek_id, row_version FROM task.thu_xoay_khoa WHERE tenant_id = ?")) {
      ps.setObject(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Object[] {
                rs.getObject(1, UUID.class),
                rs.getBytes(2),
                rs.getObject(3, UUID.class),
                rs.getInt(4)
              });
        }
      }
    }
    return out;
  }

  private static void baoPost(String path, String body) throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(baoUri.resolve(path))
                    .header("X-Vault-Token", ROOT_TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "OpenBao " + path + ": HTTP " + r.statusCode() + " " + r.body());
    }
  }

  private static OutputStreamAppender attachLogCapture(ByteArrayOutputStream out) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    OutputStreamAppender a =
        OutputStreamAppender.createAppender(
            PatternLayout.createDefaultLayout(config), null, out, "dl14-capture", false, true);
    a.start();
    config.addAppender(a);
    config.getRootLogger().addAppender(a, null, null);
    ctx.updateLoggers();
    return a;
  }

  private static void detachLogCapture(OutputStreamAppender a) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    ctx.getConfiguration().getRootLogger().removeAppender(a.getName());
    a.stop();
    ctx.updateLoggers();
  }
}
