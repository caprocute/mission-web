package vn.viettel.okrkpi.evidence.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;

/**
 * Schema {@code evidence} của SVC-08 (DV-08-01): rò rỉ tenant (RLS) do lớp cơ sở sinh trên năm
 * bảng, cộng ràng buộc của TL-03: băm SHA-256 đúng 32 byte, khoá Object Lock có cả chế độ lẫn hạn,
 * vai trò ứng dụng không xoá được phiên bản tệp và kết quả quét.
 */
class EvidenceTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final String SHA = "'\\x" + "ab".repeat(32) + "'";

  @Override
  protected List<String> schemas() {
    return List.of("evidence");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "evidence.object_meta",
        (tenant, seed) -> {
          Map<String, Object> r = audited(tenant, "object_meta");
          r.put("owner_module", "product");
          r.put("owner_ref_id", id(tenant, "evidence_version"));
          r.put("file_name_enc", new byte[] {1, 2, 3});
          r.put("dek_id", id(tenant, "dek"));
          r.put("media_type", "application/pdf");
          r.put("classification", "C2");
          r.put("retention_class", "MINH_CHUNG");
          return r;
        });
    f.put(
        "evidence.object_version",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "object_version"));
          r.put("created_by", id(tenant, "user"));
          r.put("object_meta_id", id(tenant, "object_meta"));
          r.put("version_no", 1);
          r.put("sha256", sha(seed));
          r.put("size_bytes", 1024L);
          r.put("bucket", "ev-dev-" + tenant);
          r.put("object_key", "t/" + tenant + "/sha256/ab/" + "ab".repeat(32));
          r.put("ceph_version_id", "v" + seed);
          r.put("lock_mode", "COMPLIANCE");
          r.put("lock_until", OffsetDateTime.parse("2037-01-01T00:00:00Z"));
          return r;
        });
    f.put(
        "evidence.dedup_index",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("sha256", sha(seed));
          r.put("object_key", "t/" + tenant + "/sha256/ab/" + "ab".repeat(32));
          r.put("ref_count", 1);
          return r;
        });
    f.put(
        "evidence.scan_result",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "scan_result"));
          r.put("object_version_id", id(tenant, "object_version"));
          r.put("engine", "clamav");
          r.put("engine_version", "1.4");
          r.put("verdict", "SACH");
          return r;
        });
    f.put(
        "evidence.upload_session",
        (tenant, seed) -> {
          Map<String, Object> r = audited(tenant, "upload_session");
          r.put("user_id", id(tenant, "user"));
          r.put("rgw_upload_id", "u" + seed);
          r.put("declared_sha256", sha(seed));
          r.put("declared_size", 1024L);
          r.put("expires_at", OffsetDateTime.parse("2027-01-02T00:00:00Z"));
          r.put("part_size", 8388608);
          r.put("media_type", "application/pdf");
          r.put("classification", "C1");
          r.put("owner_module", "product");
          r.put("file_name_enc", new byte[] {1, 2, 3});
          r.put("dek_id", id(tenant, "dek"));
          return r;
        });
    return f;
  }

  @Test
  void bamVaKhoaPhaiHopLe() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID meta = meta(st, t);
      assertThatThrownBy(() -> st.execute(version(t, meta, "'\\x0102'", "'COMPLIANCE'", "now()")))
          .hasMessageContaining("ck_object_version__sha256");
      assertThatThrownBy(() -> st.execute(version(t, meta, SHA, "'COMPLIANCE'", "NULL")))
          .hasMessageContaining("ck_object_version__lock");
      assertThatThrownBy(() -> st.execute(version(t, meta, SHA, "NULL", "now()")))
          .hasMessageContaining("ck_object_version__lock");
      st.execute(version(t, meta, SHA, "'COMPLIANCE'", "now() + interval '1 day'"));
    }
  }

  @Test
  void vaiTroUngDungKhongXoaPhienBanVaKetQuaQuet() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID meta = meta(st, t);
      st.execute(version(t, meta, SHA, "'COMPLIANCE'", "now() + interval '1 day'"));
    }
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      for (String table : List.of("object_version", "scan_result")) {
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM evidence." + table))
            .as(table)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      }
      assertThat(st.executeUpdate("UPDATE evidence.object_version SET scan_status = 'SACH'"))
          .isEqualTo(1);
    }
  }

  private Connection admin() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private Connection svc(UUID tenant) throws SQLException {
    Connection c = TestPostgres.connectAsTenant(jdbcUrl(), "svc_evidence", TEST_PASSWORD, tenant);
    return c;
  }

  private static UUID meta(Statement st, UUID t) throws SQLException {
    UUID id = UUID.randomUUID();
    st.execute(
        "INSERT INTO evidence.object_meta (id, tenant_id, created_by, updated_by, owner_module,"
            + " owner_ref_id, file_name_enc, dek_id, media_type, classification, retention_class)"
            + " VALUES ('"
            + id
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), 'product', gen_random_uuid(), '\\x01',"
            + " gen_random_uuid(), 'application/pdf', 'C2', 'MINH_CHUNG')");
    return id;
  }

  private static String version(UUID t, UUID meta, String sha, String mode, String until) {
    return "INSERT INTO evidence.object_version (id, tenant_id, object_meta_id, version_no, sha256,"
        + " size_bytes, bucket, object_key, ceph_version_id, lock_mode, lock_until, created_by)"
        + " VALUES (gen_random_uuid(), '"
        + t
        + "', '"
        + meta
        + "', 1, "
        + sha
        + ", 1024, 'ev-dev-x', 't/x', 'v1', "
        + mode
        + ", "
        + until
        + ", gen_random_uuid())";
  }

  private static Map<String, Object> audited(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    return r;
  }

  private static byte[] sha(int seed) {
    byte[] b = new byte[32];
    b[0] = (byte) seed;
    return b;
  }

  private static UUID id(UUID tenant, String name) {
    return UUID.nameUUIDFromBytes((tenant + "/" + name).getBytes(StandardCharsets.UTF_8));
  }
}
