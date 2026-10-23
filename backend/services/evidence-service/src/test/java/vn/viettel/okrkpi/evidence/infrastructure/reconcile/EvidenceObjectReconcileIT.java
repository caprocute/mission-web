package vn.viettel.okrkpi.evidence.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * R6 (DL-12) trên PostgreSQL 17 thật với {@code svc_evidence}; kho đối tượng giả lập trong bộ nhớ
 * theo (bucket, khoá, phiên bản) vì S3 thật đã kiểm ở {@code S3ObjectLockIT}. Quét toàn bộ phát
 * hiện tệp mất phiên bản và tệp bị thay nội dung, khớp thì {@code KHOP}; mẫu ngày tất định, chạy
 * lại cùng ngày không ghi trùng.
 */
class EvidenceObjectReconcileIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static PostgreSQLContainer<?> pg;
  private static JdbcTemplate svc;
  private static TenantDataSourceTransactionManager tm;
  private static final FakeStorage STORAGE = new FakeStorage();

  private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

  @BeforeAll
  static void start() throws SQLException {
    pg = TestPostgres.container("td_evidence_r6");
    pg.start();
    Map<String, String> env = new java.util.HashMap<>();
    for (String role : RoleBootstrapPlan.roles("evidence")) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
      bootstrap.applySchemas(admin, List.of("evidence"));
      bootstrap.setTenancyModel(admin, "pool");
    }
    new SchemaMigrator(MigrationOptions.defaults())
        .migrate(
            pg.getJdbcUrl(),
            "evidence",
            new MigrationCredentials.Credentials("mig_evidence", PASSWORD));
    DriverManagerDataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), "svc_evidence", PASSWORD);
    svc = new JdbcTemplate(ds);
    tm = new TenantDataSourceTransactionManager(ds, TenantProperties.defaults());
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @Test
  void quetToanBoPhatHienMatTepVaThayNoiDung() {
    TenantId t = TenantId.of(UUID.randomUUID());
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    EvidenceObjectReconciler r = reconciler(meters, 0.001);
    UUID ok = file(t, "Biên bản nghiệm thu", true);
    UUID lost = file(t, "Quyết định giao nhiệm vụ", false);
    UUID changed = file(t, "Báo cáo kết quả", true);
    STORAGE.tamper(changed);

    var out = r.run(t, EvidenceObjectReconciler.Mode.QUY);
    assertThat(out.status()).isEqualTo(RunStatus.LOI);
    assertThat(out.checked()).isEqualTo(3);
    assertThat(out.mismatched()).isEqualTo(2);
    ReconcileRun run = run(t, out.runId());
    assertThat(run.bucketsMismatch()).isEqualTo(2);
    assertThat(run.detail())
        .contains("\"THIEU_TEP\": 1")
        .contains("\"LECH_BAM\": 1")
        .contains(lost.toString())
        .contains(changed.toString())
        .doesNotContain(ok.toString());
    assertThat(
            meters
                .find(ReconcileMetrics.MISMATCHES)
                .tag("reconcile", EvidenceObjectReconciler.JOB)
                .counter()
                .count())
        .isEqualTo(2.0);
    // Cùng quý không chạy lại.
    assertThat(r.run(t, EvidenceObjectReconciler.Mode.QUY).status()).isEqualTo(RunStatus.DANG_CHAY);
  }

  @Test
  void mauNgayTatDinhVaKhopThiKhop() {
    TenantId t = TenantId.of(UUID.randomUUID());
    for (int i = 0; i < 20; i++) {
      file(t, "Minh chứng " + i, true);
    }
    var all = reconciler(new SimpleMeterRegistry(), 1.0).run(t, EvidenceObjectReconciler.Mode.NGAY);
    assertThat(all.status()).isEqualTo(RunStatus.KHOP);
    assertThat(all.checked()).isEqualTo(20);

    TenantId u = TenantId.of(UUID.randomUUID());
    for (int i = 0; i < 200; i++) {
      file(u, "Tệp " + i, true);
    }
    var sample =
        reconciler(new SimpleMeterRegistry(), 0.05).run(u, EvidenceObjectReconciler.Mode.NGAY);
    assertThat(sample.status()).isEqualTo(RunStatus.KHOP);
    assertThat(sample.checked()).isBetween(1, 40);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private EvidenceObjectReconciler reconciler(SimpleMeterRegistry meters, double rate) {
    return new EvidenceObjectReconciler(
        svc,
        tm,
        STORAGE,
        new ReconcileMetrics(meters, clock),
        new ObjectMapper(),
        clock,
        rate,
        5_000);
  }

  /** Ghi một tệp: siêu dữ liệu, phiên bản; {@code stored} = có trên kho. */
  private UUID file(TenantId t, String text, boolean stored) {
    byte[] content = text.getBytes(StandardCharsets.UTF_8);
    byte[] sha = sha(content);
    UUID meta = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    ObjectLocation loc = ObjectLocation.of("dev", t.value(), sha);
    String cephVersion = "v-" + version;
    inTenant(
        t,
        () -> {
          svc.update(
              "INSERT INTO evidence.object_meta (id, created_by, updated_by, owner_module,"
                  + " owner_ref_id, file_name_enc, dek_id, media_type, classification,"
                  + " retention_class) VALUES (?, gen_random_uuid(), gen_random_uuid(), 'product',"
                  + " gen_random_uuid(), '\\x01', gen_random_uuid(), 'text/plain', 'C1',"
                  + " 'MINH_CHUNG')",
              meta);
          svc.update(
              "INSERT INTO evidence.object_version (id, object_meta_id, version_no, sha256,"
                  + " size_bytes, bucket, object_key, ceph_version_id, created_by)"
                  + " VALUES (?, ?, 1, ?, ?, ?, ?, ?, gen_random_uuid())",
              version,
              meta,
              sha,
              (long) content.length,
              loc.bucket(),
              loc.key(),
              cephVersion);
          return null;
        });
    if (stored) {
      STORAGE.put(loc, cephVersion, content, version);
    }
    return version;
  }

  private ReconcileRun run(TenantId t, UUID id) {
    return inTenant(t, () -> new JdbcReconcileRunStore(svc, "evidence").find(id).orElseThrow());
  }

  private static byte[] sha(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T inTenant(TenantId t, java.util.function.Supplier<T> work) {
    try {
      return TenantContext.call(t, () -> new TransactionTemplate(tm).execute(s -> work.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Kho giả: chỉ hỗ trợ đọc theo luồng; khoá không có thì ném lỗi 404 như S3. */
  static final class FakeStorage implements ObjectStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<UUID, String> byVersion = new ConcurrentHashMap<>();

    void put(ObjectLocation loc, String versionId, byte[] content, UUID version) {
      String k = loc.bucket() + "/" + loc.key() + "@" + versionId;
      objects.put(k, content);
      byVersion.put(version, k);
    }

    void tamper(UUID version) {
      objects.computeIfPresent(
          byVersion.get(version), (k, v) -> HexFormat.of().formatHex(v).getBytes());
    }

    @Override
    public <T> T stream(ObjectLocation location, String versionId, StreamReader<T> reader)
        throws IOException {
      byte[] b = objects.get(location.bucket() + "/" + location.key() + "@" + versionId);
      if (b == null) {
        throw (NoSuchKeyException)
            NoSuchKeyException.builder().statusCode(404).message("NoSuchKey").build();
      }
      try (InputStream in = new ByteArrayInputStream(b)) {
        return reader.read(in);
      }
    }

    @Override
    public boolean ensureLockedBucket(String bucket, int lockDays) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void ensureStagingBucket(String bucket, int days) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoredObject put(
        ObjectLocation location, byte[] content, String mediaType, Instant lockUntil) {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] read(ObjectLocation location, String versionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String createMultipart(ObjectLocation location, String mediaType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String uploadPart(
        ObjectLocation location, String uploadId, int partNo, InputStream content, long length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Part> listParts(ObjectLocation location, String uploadId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void completeMultipart(ObjectLocation location, String uploadId, List<Part> parts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void abortMultipart(ObjectLocation location, String uploadId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoredObject copyLocked(
        ObjectLocation from, ObjectLocation to, String mediaType, Instant lockUntil) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void extendLock(ObjectLocation location, String versionId, Instant lockUntil) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteStaging(ObjectLocation location) {
      throw new UnsupportedOperationException();
    }

    @Override
    public URI presignGet(
        ObjectLocation location,
        String versionId,
        Duration ttl,
        String mediaType,
        String contentDisposition) {
      throw new UnsupportedOperationException();
    }
  }
}
