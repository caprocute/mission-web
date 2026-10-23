package vn.viettel.okrkpi.dossier.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code dossier} (CORE-06-01) ở CSDL pool: rò rỉ tenant (RLS) do lớp cơ sở sinh trên sáu
 * bảng, cộng ràng buộc: một gói cho mỗi đơn vị, kỳ, loại; gói đã nộp không sửa trừ chuyển lưu trữ
 * (BR-11-10); góp ý chỉ thêm.
 */
class DossierTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final String SHA = "'\\x" + "ab".repeat(32) + "'";

  @Override
  protected List<String> schemas() {
    return List.of("dossier");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "dossier.review_meeting",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "review_meeting");
          r.put("org_unit_id", id(tenant, "org"));
          r.put("meeting_type", "KIEM_DIEM_NAM");
          r.put("scheduled_at", OffsetDateTime.parse("2027-12-20T08:00:00Z"));
          r.put("chair_person_id", id(tenant, "chair"));
          r.put("secretary_person_id", id(tenant, "secretary"));
          return r;
        });
    f.put(
        "dossier.review_comment",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "review_comment");
          r.put("meeting_id", id(tenant, "review_meeting"));
          r.put("subject_person_id", id(tenant, "person"));
          r.put("commenter_person_id", id(tenant, "commenter"));
          r.put("comment_kind", "GOP_Y");
          r.put("kind", "HAN_CHE");
          r.put("phase", "HOI_NGHI");
          r.put("comment_enc", new byte[] {1, 2, 3});
          r.put("dek_id", id(tenant, "dek"));
          return r;
        });
    f.put(
        "dossier.dossier_package",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "dossier_package");
          r.put("code", "DP-2027-Q-" + (1000 + seed));
          r.put("org_unit_id", id(tenant, "org"));
          r.put("package_kind", "QUY");
          return r;
        });
    f.put(
        "dossier.form_instance",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "form_instance");
          r.put("dossier_package_id", id(tenant, "dossier_package"));
          r.put("profile_id", id(tenant, "profile"));
          r.put("form_code", "MAU_2");
          r.put("template_version", "1.0.0");
          r.put("data_snapshot_hash", new byte[32]);
          return r;
        });
    // CORE-06-08: tác vụ xuất và gói con không có period_id.
    f.put(
        "dossier.package_export",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "package_export");
          r.remove("period_id");
          r.put("dossier_package_id", id(tenant, "dossier_package"));
          return r;
        });
    f.put(
        "dossier.package_transfer",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "package_transfer");
          r.remove("period_id");
          r.put("dossier_package_id", id(tenant, "dossier_package"));
          r.put("batch_id", id(tenant, "batch"));
          r.put("profile_id", id(tenant, "profile"));
          r.put("person_id", id(tenant, "person"));
          r.put("destination", "BTC_TW");
          return r;
        });
    f.put(
        "dossier.special_case",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "special_case");
          r.put("person_id", id(tenant, "person"));
          r.put("case_code", "NGHI_THAI_SAN");
          r.put("detected_by", "THU_CONG");
          r.put("effect", "CHO_KET_LUAN");
          r.put("legal_ref", "PL 6 mục 3");
          return r;
        });
    f.put(
        "dossier.remediation_item",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "remediation_item");
          r.put("person_id", id(tenant, "person"));
          r.put("meeting_id", id(tenant, "review_meeting"));
          r.put("review_comment_id", id(tenant, "review_comment"));
          r.put("content_enc", new byte[] {4, 5, 6});
          r.put("dek_id", id(tenant, "dek"));
          return r;
        });
    return f;
  }

  @Test
  void haiGoiCungDonViKyLoaiBiChan() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID org = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(packageSql(UUID.randomUUID(), t, org, "DP-2027-Q-0001", "NULL", "'DANG_LAP'"));
      assertThatThrownBy(
              () ->
                  st.execute(
                      packageSql(
                          UUID.randomUUID(), t, org, "DP-2027-Q-0002", "NULL", "'DANG_LAP'")))
          .hasMessageContaining("duplicate key")
          .hasMessageContaining("org_unit");
      assertThatThrownBy(
              () ->
                  st.execute(
                      packageSql(
                          UUID.randomUUID(),
                          t,
                          UUID.randomUUID(),
                          "DP-2027-Q-0001",
                          "NULL",
                          "'DANG_LAP'")))
          .hasMessageContaining("duplicate key")
          // V3 (CORE-06-07): ràng buộc mã gồm version_no, tên chỉ mục phân vùng thành
          // ..._code_ver_key.
          .hasMessageContaining("code_ver");
      // Gói con diện TW theo người cùng đơn vị, kỳ, loại được phép, mỗi người một gói.
      String person = "'" + UUID.randomUUID() + "'";
      st.execute(packageSql(UUID.randomUUID(), t, org, "DP-2027-Q-0003", person, "'DANG_LAP'"));
      assertThatThrownBy(
              () ->
                  st.execute(
                      packageSql(
                          UUID.randomUUID(), t, org, "DP-2027-Q-0004", person, "'DANG_LAP'")))
          .hasMessageContaining("duplicate key")
          .hasMessageContaining("org_unit");
    }
  }

  @Test
  void goiDaNopKhongSuaTruChuyenLuuTru() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID pkg = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(packageSql(pkg, t, UUID.randomUUID(), "DP-2027-N-0001", "NULL", "'CHO_KY'"));
      // Trước khi nộp vẫn sửa được.
      st.execute(
          "UPDATE dossier.dossier_package SET deadline = date '2028-01-15' WHERE id = '"
              + pkg
              + "'");
      st.execute(
          "UPDATE dossier.dossier_package SET status = 'DA_NOP', submitted_at = now(),"
              + " package_sha256 = "
              + SHA
              + " WHERE id = '"
              + pkg
              + "'");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE dossier.dossier_package SET deadline = date '2028-02-01' WHERE id = '"
                          + pkg
                          + "'"))
          .hasMessageContaining("đã nộp");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE dossier.dossier_package SET status = 'DA_LUU_TRU', object_key = 'x'"
                          + " WHERE id = '"
                          + pkg
                          + "'"))
          .hasMessageContaining("đã nộp");
      assertThatThrownBy(
              () -> st.execute("DELETE FROM dossier.dossier_package WHERE id = '" + pkg + "'"))
          .hasMessageContaining("permission denied");
      st.execute(
          "UPDATE dossier.dossier_package SET status = 'DA_LUU_TRU', row_version = row_version + 1"
              + " WHERE id = '"
              + pkg
              + "'");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE dossier.dossier_package SET status = 'DA_NOP' WHERE id = '"
                          + pkg
                          + "'"))
          .hasMessageContaining("đã nộp");
      try (ResultSet rs =
          st.executeQuery("SELECT status FROM dossier.dossier_package WHERE id = '" + pkg + "'")) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("DA_LUU_TRU");
      }
    }
  }

  @Test
  void gopYChiThemKhongSuaKhongXoa() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID meeting = UUID.randomUUID();
    UUID comment = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO dossier.review_meeting (id, created_by, updated_by, period_id, period_bucket,"
              + " org_unit_id, meeting_type, scheduled_at, chair_person_id, secretary_person_id)"
              + " VALUES ('"
              + meeting
              + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
              + SAMPLE_PERIOD_BUCKET
              + ", gen_random_uuid(), 'KIEM_DIEM_QUY', now(), gen_random_uuid(), gen_random_uuid())");
      st.execute(
          "INSERT INTO dossier.review_comment (id, created_by, updated_by, period_id, period_bucket,"
              + " meeting_id, subject_person_id, commenter_person_id, comment_kind, kind, phase,"
              + " comment_enc, dek_id) VALUES ('"
              + comment
              + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
              + SAMPLE_PERIOD_BUCKET
              + ", '"
              + meeting
              + "', gen_random_uuid(), gen_random_uuid(), 'GOP_Y', 'UU_DIEM', 'TRUOC_HOI_NGHI',"
              + " '\\x01', gen_random_uuid())");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE dossier.review_comment SET kind = 'HAN_CHE' WHERE id = '"
                          + comment
                          + "'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(
              () -> st.execute("DELETE FROM dossier.review_comment WHERE id = '" + comment + "'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE dossier.review_meeting SET minutes_sha256 = '\\x01' WHERE id = '"
                          + meeting
                          + "'"))
          .hasMessageContaining("ck_review_meeting__minutes_sha256");
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Connection admin() throws SQLException {
    Connection c =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ensurePeriodPartitionsCreated(jdbcUrl(), c, schemas());
    return c;
  }

  private Connection svc(UUID tenant) throws SQLException {
    admin().close();
    Connection c = DriverManager.getConnection(jdbcUrl(), "svc_dossier", TEST_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    return c;
  }

  private static String packageSql(
      UUID id, UUID t, UUID org, String code, String person, String status) {
    return "INSERT INTO dossier.dossier_package (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, code, org_unit_id, person_id, package_kind, status) VALUES ('"
        + id
        + "', '"
        + t
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + code
        + "', '"
        + org
        + "', "
        + person
        + ", 'QUY', "
        + status
        + ")";
  }

  private static Map<String, Object> periodRow(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
    r.put("period_id", id(tenant, "period"));
    return r;
  }

  private static UUID id(UUID tenant, String name) {
    return UUID.nameUUIDFromBytes((tenant + "/" + name).getBytes(StandardCharsets.UTF_8));
  }
}
