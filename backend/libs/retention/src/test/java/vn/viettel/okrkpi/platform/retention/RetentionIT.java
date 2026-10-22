package vn.viettel.okrkpi.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
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
import vn.viettel.okrkpi.shared.crypto.DekDestroyedException;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.EncryptedField;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.OpenBaoTransitClient;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * DL-13 trên PostgreSQL 17 (bootstrap và migration thật, RLS FORCE, phân vùng bằng {@code
 * fn_create_period_partitions} và pg_partman), S3Mock có Object Lock và OpenBao 2.1.0 chế độ dev.
 */
class RetentionIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final String ROOT_TOKEN = "dev-openbao-root-token";
  private static final String BUCKET = "luu-tru-thu";
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final Clock TODAY =
      Clock.fixed(Instant.parse("2026-10-22T02:00:00Z"), ZoneOffset.UTC);
  private static final ActorId JOB =
      ActorId.of(UUID.fromString("01950000-0000-7000-8000-00000000b001"));
  private static final UUID A = UUID.fromString("01950000-0000-7000-8000-0000000000a1");
  private static final UUID B = UUID.fromString("01950000-0000-7000-8000-0000000000b2");

  private static PostgreSQLContainer<?> pg;
  private static GenericContainer<?> s3mock;
  private static GenericContainer<?> bao;
  private static S3Client s3;
  private static URI baoUri;
  private static Path tokenFile;
  private static Path work;
  private static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();
  private static final RetentionAudit REPORT = new RetentionAudit(AUDIT::add, JOB);

  @BeforeAll
  static void start() throws Exception {
    pg = TestPostgres.container("td_retention");
    pg.start();
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin = admin()) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    }
    SchemaMigrator migrator = new SchemaMigrator(MigrationOptions.defaults());
    for (String schema : List.of("keyring", "config", "task", "product", "evidence")) {
      migrator.migrate(
          pg.getJdbcUrl(),
          schema,
          new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), PASSWORD));
    }
    // Bảng tối thiểu của mission-core và evidence-service mà job đọc, tạo bằng mig_ như migration.
    as(
        "mig_config",
        "CREATE TABLE config.tenant_setting (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,"
            + " setting_key varchar(100) NOT NULL, scope_unit_id uuid, value jsonb NOT NULL,"
            + " value_schema varchar(100) NOT NULL)",
        "SELECT config.fn_enable_tenant_rls('config.tenant_setting')");
    as(
        "mig_evidence",
        "CREATE TABLE evidence.object_meta (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,"
            + " dek_id uuid NOT NULL, legal_hold boolean NOT NULL DEFAULT false)",
        "CREATE TABLE evidence.object_version (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,"
            + " object_meta_id uuid NOT NULL, version_no int NOT NULL, bucket varchar(63) NOT"
            + " NULL, object_key varchar(512) NOT NULL, ceph_version_id varchar(64) NOT NULL)",
        "SELECT evidence.fn_enable_tenant_rls('evidence.object_meta')",
        "SELECT evidence.fn_enable_tenant_rls('evidence.object_version')");
    for (String schema : List.of("task", "product")) {
      as(
          "mig_" + schema,
          "CREATE TABLE "
              + schema
              + ".thu_luu_tru (id uuid NOT NULL, tenant_id uuid NOT NULL, period_bucket int NOT"
              + " NULL, ten text, so_luong numeric(18,2), ghi_chu_enc bytea, dek_id uuid, xong"
              + " boolean, tao_luc timestamptz, legal_hold boolean NOT NULL DEFAULT false,"
              + " du_lieu jsonb, PRIMARY KEY (period_bucket, tenant_id, id)) PARTITION BY LIST"
              + " (period_bucket)",
          "SELECT " + schema + ".fn_enable_tenant_rls('" + schema + ".thu_luu_tru')");
    }
    as(
        "mig_task",
        "SELECT task.fn_create_period_partitions('task.thu_luu_tru', 20231, 0)",
        "SELECT task.fn_create_period_partitions('task.thu_luu_tru', 20232, 0)",
        "SELECT task.fn_create_period_partitions('task.thu_luu_tru', 20261, 0)",
        // Bảng thứ hai chia băm như CSDL pool.
        "CREATE TABLE task.thu_chi_tiet (id uuid NOT NULL, tenant_id uuid NOT NULL,"
            + " period_bucket int NOT NULL, mo_ta text, PRIMARY KEY (period_bucket, tenant_id,"
            + " id)) PARTITION BY LIST (period_bucket)",
        "SELECT task.fn_enable_tenant_rls('task.thu_chi_tiet')",
        "SELECT task.fn_create_period_partitions('task.thu_chi_tiet', 20231, 4)");
    as(
        "mig_product",
        "SELECT product.fn_create_period_partitions('product.thu_luu_tru', 20224, 0)");

    s3mock =
        new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090)
            .waitingFor(Wait.forListeningPort());
    s3mock.start();
    s3 =
        S3Client.builder()
            .endpointOverride(
                URI.create("http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090)))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .httpClient(UrlConnectionHttpClient.create())
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("thu", "thu")))
            .build();
    s3.createBucket(b -> b.bucket(BUCKET).objectLockEnabledForBucket(true));
    s3.createBucket(b -> b.bucket("minh-chung").objectLockEnabledForBucket(true));

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
    work = Files.createTempDirectory("dl13-");
  }

  @AfterAll
  static void stop() throws Exception {
    if (tokenFile != null) {
      Files.deleteIfExists(tokenFile);
    }
    for (GenericContainer<?> c : new GenericContainer<?>[] {bao, s3mock, pg}) {
      if (c != null) {
        c.stop();
      }
    }
  }

  @Test
  void luuTruPhanVungQuaHanNongXuatParquetKeKhaiKhopObjectLockRoiTachPhanVung() throws Exception {
    // Tenant A rút thời hạn nóng lớp THUC_HIEN còn 1 năm, B giữ mặc định 3 năm: job theo B.
    setting(A, "retention.THUC_HIEN", "{\"hot\":\"P1Y\",\"total\":\"P12Y\"}");
    DekService deks = dekService(jdbc("svc_keyring"), TODAY);
    FieldEncryptor enc = new FieldEncryptor(deks, new JcaAesGcmCryptoProvider());
    UUID held = UUID.randomUUID();
    EncryptedField c2 =
        enc.encryptField(
            A,
            DekPurpose.TEP_MINH_CHUNG,
            List.of("task.thu_luu_tru.ghi_chu_enc", held.toString()),
            "Ghi chú mật".getBytes(StandardCharsets.UTF_8));
    insert(A, 20231, held, "Báo cáo quý I", new BigDecimal("12.50"), c2, true, true);
    insert(A, 20231, null, "", null, null, false, false);
    insert(B, 20231, null, null, new BigDecimal("-3.00"), null, null, false);
    insert(B, 20232, null, "Quý II", BigDecimal.ONE, null, true, false);
    insert(A, 20261, null, "Quý I/2026 còn nóng", BigDecimal.TEN, null, true, false);
    for (int i = 0; i < 25; i++) {
      UUID tenant = i % 2 == 0 ? A : B;
      String moTa = "Chi tiết " + i;
      TenantTx.run(
          jdbc("svc_task"),
          tenant,
          conn -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "INSERT INTO task.thu_chi_tiet (id, tenant_id, period_bucket, mo_ta) VALUES"
                        + " (?, ?, 20231, ?)")) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, tenant);
              ps.setString(3, moTa);
              return ps.executeUpdate();
            }
          });
    }

    ArchiveJob.Result r = archiveJob("task", store(BUCKET), ClassMap.defaults()).run();

    assertThat(r.archived())
        .extracting(ArchiveJob.Group::periodBucket, ArchiveJob.Group::tables)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(20231, List.of("thu_chi_tiet", "thu_luu_tru")),
            org.assertj.core.groups.Tuple.tuple(20232, List.of("thu_luu_tru")));
    assertThat(tables("task"))
        .contains("thu_luu_tru_p20261")
        .doesNotContain(
            "thu_luu_tru_p20231",
            "thu_luu_tru_p20232",
            "thu_chi_tiet_p20231",
            "thu_chi_tiet_p20231_h00");
    assertThat(count("task.thu_luu_tru", A)).isEqualTo(1);

    String key = r.archived().get(0).manifestKey();
    Manifest m =
        JSON.readValue(
            s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray(), Manifest.class);
    assertThat(m.retentionClass()).isEqualTo(RetentionClass.THUC_HIEN);
    // Tổng thời hạn giữ lâu nhất là 12 năm của A (B mặc định 10 năm).
    assertThat(m.retainUntil()).isEqualTo(LocalDate.of(2035, 4, 1));
    Manifest.TableEntry main =
        m.tables().stream().filter(t -> t.table().equals("thu_luu_tru")).findFirst().orElseThrow();
    assertThat(main.rows()).isEqualTo(3);
    assertThat(main.files()).extracting(Manifest.FileEntry::tenant).containsExactly(A, B);
    assertThat(main.files()).extracting(Manifest.FileEntry::legalHold).containsExactly(true, false);
    assertThat(m.tables().get(0).rows()).isEqualTo(25);
    for (Manifest.TableEntry t : m.tables()) {
      for (Manifest.FileEntry f : t.files()) {
        Path p = work.resolve("kiem-" + UUID.randomUUID());
        store(BUCKET).download(f.key(), p);
        assertThat(ArchiveStore.sha256(p)).isEqualTo(f.sha256());
        Files.delete(p);
        var ret = s3.getObjectRetention(b -> b.bucket(BUCKET).key(f.key())).retention();
        assertThat(ret.mode().toString()).isEqualTo(ObjectLockMode.COMPLIANCE.toString());
        assertThat(ret.retainUntilDate()).isEqualTo(Instant.parse("2035-04-01T00:00:00Z"));
        assertThat(s3.getObjectLegalHold(b -> b.bucket(BUCKET).key(f.key())).legalHold().status())
            .isEqualTo(
                f.legalHold() ? ObjectLockLegalHoldStatus.ON : ObjectLockLegalHoldStatus.OFF);
      }
    }
    assertThat(AUDIT)
        .filteredOn(e -> e.action().equals("RETENTION_ARCHIVE"))
        .hasSizeGreaterThanOrEqualTo(3);
    assertThat(AUDIT.toString()).doesNotContain("Ghi chú mật", "Báo cáo quý I");

    // Chạy lại không còn gì để lưu trữ.
    assertThat(archiveJob("task", store(BUCKET), ClassMap.defaults()).run().archived()).isEmpty();

    // Khôi phục: thiếu phê duyệt thì từ chối trước khi đọc gì.
    RestoreTool tool = restoreTool(TODAY);
    assertThatThrownBy(() -> tool.restore(key, A, null, Duration.ofDays(7)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new Approval(ActorId.of(A), ActorId.of(A), "YC-2026-01"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(databases()).noneMatch(d -> d.startsWith("khoi_phuc_"));

    Approval ok =
        new Approval(ActorId.of(UUID.randomUUID()), ActorId.of(UUID.randomUUID()), "YC-2026-01");
    RestoreTool.Result restored = tool.restore(key, A, ok, Duration.ofDays(7));
    assertThat(restored.database()).isEqualTo("khoi_phuc_yc_2026_01");
    assertThat(restored.rows()).containsEntry("thu_luu_tru", 2L).containsEntry("thu_chi_tiet", 13L);
    try (Connection c =
            DriverManager.getConnection(
                pg.getJdbcUrl().replace("/td_retention", "/khoi_phuc_yc_2026_01"),
                pg.getUsername(),
                pg.getPassword());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT ten, so_luong, ghi_chu_enc, dek_id, xong, tao_luc, legal_hold, du_lieu::text"
                    + " FROM task.thu_luu_tru WHERE id = ?")) {
      ps.setObject(1, held);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("Báo cáo quý I");
        assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("12.50");
        assertThat(rs.getBytes(3)).isEqualTo(c2.ciphertext());
        assertThat(rs.getObject(4, UUID.class)).isEqualTo(c2.dekId());
        assertThat(rs.getBoolean(5)).isTrue();
        assertThat(rs.getObject(6, OffsetDateTime.class).toInstant())
            .isEqualTo(Instant.parse("2023-02-15T03:04:05.123456Z"));
        assertThat(rs.getBoolean(7)).isTrue();
        assertThat(rs.getString(8)).isEqualTo("{\"muc\": 1}");
      }
      // Chuỗi rỗng và NULL được giữ phân biệt.
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT count(*) FILTER (WHERE ten = ''), count(*) FILTER (WHERE ten IS NULL)"
                      + " FROM task.thu_luu_tru")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
        assertThat(rs.getInt(2)).isZero();
      }
    }
    // Bản mã trong CSDL tạm vẫn là bản mã, chỉ mở được bằng DEK của tenant.
    assertThat(
            enc.decryptToString(
                A, List.of("task.thu_luu_tru.ghi_chu_enc", held.toString()), c2.ciphertext()))
        .isEqualTo("Ghi chú mật");
    assertThat(AUDIT)
        .filteredOn(e -> e.action().equals("RETENTION_RESTORE"))
        .singleElement()
        .satisfies(e -> assertThat(e.attributes()).containsEntry("phieu", "YC-2026-01"));

    assertThat(restoreTool(TODAY).cleanup()).isEmpty();
    assertThat(restoreTool(Clock.offset(TODAY, Duration.ofDays(8))).cleanup())
        .containsExactly("khoi_phuc_yc_2026_01");
    assertThat(databases()).noneMatch(d -> d.startsWith("khoi_phuc_"));
  }

  @Test
  void loiGhiKhoGanLaiPhanVungVaBatLaiForceRls() throws Exception {
    insertProduct(A, 20224);
    insertProduct(B, 20224);

    assertThatThrownBy(
            () -> archiveJob("product", store("bucket-khong-ton-tai"), ClassMap.defaults()).run())
        .isInstanceOf(RuntimeException.class);

    assertThat(attachedPartitions("product", "thu_luu_tru")).containsExactly("thu_luu_tru_p20224");
    assertThat(forceRls("product", "thu_luu_tru_p20224")).isTrue();
    assertThat(count("product.thu_luu_tru", A)).isEqualTo(1);
    assertThat(count("product.thu_luu_tru", B)).isEqualTo(1);
  }

  @Test
  void huyDekHetTongThoiHanBoQuaGiuPhapLyVaGiaHanObjectLock() throws Exception {
    UUID t = UUID.fromString("01950000-0000-7000-8000-0000000000c3");
    Clock jan2014 = Clock.fixed(Instant.parse("2014-01-10T00:00:00Z"), ZoneOffset.UTC);
    Clock feb2014 = Clock.fixed(Instant.parse("2014-02-10T00:00:00Z"), ZoneOffset.UTC);
    JdbcTemplate keyring = jdbc("svc_keyring");
    List<String> aad = List.of("evidence.object_meta.file_name_enc", UUID.randomUUID().toString());
    EncryptedField jan =
        new FieldEncryptor(dekService(keyring, jan2014), new JcaAesGcmCryptoProvider())
            .encryptField(
                t, DekPurpose.TEP_MINH_CHUNG, aad, "a.pdf".getBytes(StandardCharsets.UTF_8));
    EncryptedField feb =
        new FieldEncryptor(dekService(keyring, feb2014), new JcaAesGcmCryptoProvider())
            .encryptField(
                t, DekPurpose.TEP_MINH_CHUNG, aad, "b.pdf".getBytes(StandardCharsets.UTF_8));
    EncryptedField nhanXet =
        new FieldEncryptor(dekService(keyring, jan2014), new JcaAesGcmCryptoProvider())
            .encryptField(
                t, DekPurpose.NHAN_XET_DANH_GIA, aad, "x".getBytes(StandardCharsets.UTF_8));
    EncryptedField now =
        new FieldEncryptor(dekService(keyring, TODAY), new JcaAesGcmCryptoProvider())
            .encryptField(
                t, DekPurpose.TEP_MINH_CHUNG, aad, "c.pdf".getBytes(StandardCharsets.UTF_8));
    // Tệp của tháng 2/2014 đang giữ pháp lý, Object Lock đến 01/11/2026.
    String versionId =
        s3.putObject(
                b ->
                    b.bucket("minh-chung")
                        .key("t/" + t + "/sha256/ab/abc")
                        .objectLockMode(ObjectLockMode.COMPLIANCE)
                        .objectLockRetainUntilDate(Instant.parse("2026-11-01T00:00:00Z")),
                RequestBody.fromString("noi dung"))
            .versionId();
    UUID meta = UUID.randomUUID();
    TenantTx.run(
        jdbc("svc_evidence"),
        t,
        conn -> {
          try (PreparedStatement ps =
                  conn.prepareStatement(
                      "INSERT INTO evidence.object_meta (id, tenant_id, dek_id, legal_hold) VALUES"
                          + " (?, ?, ?, true)");
              PreparedStatement pv =
                  conn.prepareStatement(
                      "INSERT INTO evidence.object_version (id, tenant_id, object_meta_id,"
                          + " version_no, bucket, object_key, ceph_version_id) VALUES (?, ?, ?, 1,"
                          + " 'minh-chung', ?, ?)")) {
            ps.setObject(1, meta);
            ps.setObject(2, t);
            ps.setObject(3, feb.dekId());
            ps.executeUpdate();
            pv.setObject(1, UUID.randomUUID());
            pv.setObject(2, t);
            pv.setObject(3, meta);
            pv.setString(4, "t/" + t + "/sha256/ab/abc");
            pv.setString(5, versionId == null ? "null" : versionId);
            return pv.executeUpdate();
          }
        });

    CryptoEraseJob job =
        new CryptoEraseJob(
            keyring,
            new RetentionRules(jdbc("ro_config"), JSON),
            () -> List.of(t),
            ClassMap.defaults(),
            new LegalHoldSource(jdbc("ro_evidence"), null, List.of()),
            store(BUCKET),
            Period.ofYears(1),
            REPORT,
            TODAY);
    CryptoEraseJob.Result r = job.run();

    assertThat(r.destroyed()).containsExactly(jan.dekId());
    assertThat(r.held())
        .singleElement()
        .satisfies(
            h -> {
              assertThat(h.dekId()).isEqualTo(feb.dekId());
              assertThat(h.objectsExtended()).isEqualTo(1);
            });
    assertThat(
            s3.getObjectRetention(
                    b ->
                        b.bucket("minh-chung")
                            .key("t/" + t + "/sha256/ab/abc")
                            .versionId(versionId))
                .retention()
                .retainUntilDate())
        .isEqualTo(Instant.parse("2027-10-22T00:00:00Z"));

    // Bản mã của DEK đã huỷ không mở được, lỗi có kiểm soát; dữ liệu khác vẫn đọc được.
    FieldEncryptor fresh =
        new FieldEncryptor(dekService(keyring, TODAY), new JcaAesGcmCryptoProvider());
    assertThatThrownBy(() -> fresh.decryptToString(t, aad, jan.ciphertext()))
        .isInstanceOf(DekDestroyedException.class);
    assertThat(fresh.decryptToString(t, aad, feb.ciphertext())).isEqualTo("b.pdf");
    assertThat(fresh.decryptToString(t, aad, nhanXet.ciphertext())).isEqualTo("x");
    assertThat(fresh.decryptToString(t, aad, now.ciphertext())).isEqualTo("c.pdf");
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT status, length(wrapped_dek), destroyed_at IS NOT NULL FROM keyring.dek WHERE"
                    + " id = ?")) {
      ps.setObject(1, jan.dekId());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("DESTROYED");
        assertThat(rs.getInt(2)).isZero();
        assertThat(rs.getBoolean(3)).isTrue();
      }
    }
    // Chạy lại không huỷ thêm.
    assertThat(job.run().destroyed()).isEmpty();
  }

  @Test
  void xoaPhanVungLopKyThuatVanHanhKhongKeoDaiThoiHanVaTuChoiBangGiuPhapLy() throws Exception {
    as(
        "mig_task",
        "SELECT partman.create_partition_time('task.outbox_event',"
            + " ARRAY['2026-01-05 00:00+00'::timestamptz])",
        "CREATE TABLE task.thu_nhat_ky (id uuid NOT NULL, tenant_id uuid NOT NULL, created_at"
            + " timestamptz NOT NULL, PRIMARY KEY (id, created_at)) PARTITION BY RANGE"
            + " (created_at)",
        "SELECT partman.create_parent(p_parent_table => 'task.thu_nhat_ky', p_control =>"
            + " 'created_at', p_interval => '1 day', p_premake => 2, p_jobmon => false)",
        "CREATE TABLE task.thu_giu (id uuid NOT NULL, tenant_id uuid NOT NULL, created_at"
            + " timestamptz NOT NULL, legal_hold boolean NOT NULL DEFAULT false, PRIMARY KEY (id,"
            + " created_at)) PARTITION BY RANGE (created_at)",
        "SELECT partman.create_parent(p_parent_table => 'task.thu_giu', p_control =>"
            + " 'created_at', p_interval => '1 day', p_premake => 2, p_jobmon => false)");
    assertThat(tables("task")).contains("outbox_event_p20260105");
    Properties p = new Properties();
    p.setProperty("lop.task.thu_nhat_ky", "VAN_HANH");
    p.setProperty("lop.task.thu_giu", "VAN_HANH");

    PartitionDropJob.Result r =
        new PartitionDropJob(
                new DriverManagerDataSource(pg.getJdbcUrl(), "mig_task", PASSWORD),
                "task",
                new RetentionRules(jdbc("ro_config"), JSON),
                () -> List.of(A, B),
                new ClassMap(p))
            .run();

    assertThat(r.refused()).containsExactly("task.thu_giu");
    assertThat(r.tables())
        .extracting(PartitionDropJob.TableResult::table, PartitionDropJob.TableResult::retention)
        .contains(
            org.assertj.core.groups.Tuple.tuple("outbox_event", "3 days"),
            org.assertj.core.groups.Tuple.tuple("idempotency_key", "2 days"),
            org.assertj.core.groups.Tuple.tuple("thu_nhat_ky", "366 days"));
    assertThat(tables("task")).doesNotContain("outbox_event_p20260105");
    try (Connection c = admin();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT retention FROM partman.part_config WHERE parent_table = 'task.thu_giu'")) {
      rs.next();
      assertThat(rs.getString(1)).isNull();
    }
  }

  // ------------------------------------------------------------------ hỗ trợ

  private static ArchiveJob archiveJob(String schema, ArchiveStore store, ClassMap classes) {
    return new ArchiveJob(
        new DriverManagerDataSource(pg.getJdbcUrl(), "mig_" + schema, PASSWORD),
        schema,
        new RetentionRules(jdbc("ro_config"), JSON),
        () -> List.of(A, B),
        classes,
        store,
        "luu-tru",
        REPORT,
        JSON,
        work,
        TODAY);
  }

  private static RestoreTool restoreTool(Clock clock) {
    return new RestoreTool(
        pg.getJdbcUrl(),
        pg.getUsername(),
        pg.getPassword(),
        store(BUCKET),
        REPORT,
        JSON,
        work,
        clock);
  }

  private static ArchiveStore store(String bucket) {
    return new ArchiveStore(s3, bucket);
  }

  private static void setting(UUID tenant, String key, String value) {
    TenantTx.run(
        jdbc("svc_config"),
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO config.tenant_setting (id, tenant_id, setting_key, value,"
                      + " value_schema) VALUES (?, ?, ?, ?::jsonb, 'retention-class.v1')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, key);
            ps.setString(4, value);
            return ps.executeUpdate();
          }
        });
  }

  private static void insert(
      UUID tenant,
      int bucket,
      UUID id,
      String ten,
      BigDecimal soLuong,
      EncryptedField c2,
      Boolean xong,
      boolean hold) {
    TenantTx.run(
        jdbc("svc_task"),
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO task.thu_luu_tru (id, tenant_id, period_bucket, ten, so_luong,"
                      + " ghi_chu_enc, dek_id, xong, tao_luc, legal_hold, du_lieu) VALUES (?, ?, ?,"
                      + " ?, ?, ?, ?, ?, ?, ?, ?::jsonb)")) {
            ps.setObject(1, id == null ? UUID.randomUUID() : id);
            ps.setObject(2, tenant);
            ps.setInt(3, bucket);
            ps.setString(4, ten);
            ps.setBigDecimal(5, soLuong);
            ps.setBytes(6, c2 == null ? null : c2.ciphertext());
            ps.setObject(7, c2 == null ? null : c2.dekId());
            ps.setObject(8, xong);
            ps.setObject(9, OffsetDateTime.parse("2023-02-15T10:04:05.123456+07:00"));
            ps.setBoolean(10, hold);
            ps.setString(11, c2 == null ? null : "{\"muc\": 1}");
            return ps.executeUpdate();
          }
        });
  }

  private static void insertProduct(UUID tenant, int bucket) {
    TenantTx.run(
        jdbc("svc_product"),
        tenant,
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO product.thu_luu_tru (id, tenant_id, period_bucket, ten) VALUES (?,"
                      + " ?, ?, 'x')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setInt(3, bucket);
            return ps.executeUpdate();
          }
        });
  }

  private static int count(String table, UUID tenant) {
    String svc = "svc_" + table.substring(0, table.indexOf('.'));
    return TenantTx.run(
        jdbc(svc),
        tenant,
        conn -> {
          try (Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
          }
        });
  }

  private static List<String> tables(String schema) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = ? AND c.relkind IN ('r', 'p')")) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static List<String> attachedPartitions(String schema, String parent) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT ch.relname FROM pg_inherits i JOIN pg_class ch ON ch.oid = i.inhrelid"
                    + " WHERE i.inhparent = (? || '.' || ?)::regclass")) {
      ps.setString(1, schema);
      ps.setString(2, parent);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static boolean forceRls(String schema, String table) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT relforcerowsecurity FROM pg_class WHERE oid = (? || '.' || ?)::regclass")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static List<String> databases() throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = admin();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT datname FROM pg_database")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static void as(String role, String... sql) throws Exception {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), role, PASSWORD);
        Statement st = c.createStatement()) {
      for (String s : sql) {
        st.execute(s);
      }
    }
  }

  private static DekService dekService(JdbcTemplate jdbc, Clock clock) {
    CryptoProperties props = new CryptoProperties();
    props.setOpenBaoBaseUrl(baoUri);
    props.setTokenFile(tokenFile);
    props.setTransitMount("transit");
    props.setKekPrefix("kek-tenant-");
    return new DekService(
        jdbc,
        new OpenBaoTransitClient(props, JSON, clock),
        new JcaAesGcmCryptoProvider(),
        new DekCache(1_000, Duration.ofMinutes(15), clock),
        clock);
  }

  private static JdbcTemplate jdbc(String role) {
    return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), role, PASSWORD));
  }

  private static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
      throw new IllegalStateException("OpenBao " + path + ": HTTP " + r.statusCode());
    }
  }
}
