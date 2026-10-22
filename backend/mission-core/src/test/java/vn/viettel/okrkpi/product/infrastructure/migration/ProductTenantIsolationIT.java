package vn.viettel.okrkpi.product.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code product} (CORE-03-01) ở CSDL pool: rò rỉ tenant (RLS) do lớp cơ sở sinh trên năm
 * bảng, cộng ràng buộc toàn vẹn của TL-03: phiên bản đã nộp bất biến, AI điền sẵn phải được xác
 * nhận, chỉ tệp sạch mới vào nghiệm thu, không huỷ xác nhận, không xoá, người nghiệm thu khác người
 * nộp (BR-07-06) và mức chất lượng năm mức.
 */
class ProductTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final String SHA = "'\\x" + "ab".repeat(32) + "'";

  @Override
  protected List<String> schemas() {
    return List.of("product");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "product.deliverable",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "deliverable");
          r.put("task_id", id(tenant, "task"));
          r.put("name", "Báo cáo chuyên đề");
          r.put("deliverable_type", "BAO_CAO");
          r.put("unit", "Báo cáo");
          r.put("quantity_planned", new BigDecimal("1.0000"));
          return r;
        });
    f.put(
        "product.evidence",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "evidence");
          r.put("deliverable_id", id(tenant, "deliverable"));
          r.put("task_id", id(tenant, "task"));
          r.put("title", "Báo cáo đã ký");
          r.put("source_type", "TAI_LEN");
          return r;
        });
    f.put(
        "product.evidence_version",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "evidence_version");
          r.put("evidence_id", id(tenant, "evidence"));
          r.put("task_id", id(tenant, "task"));
          r.put("version_no", 1);
          r.put("source", "TAI_LEN");
          return r;
        });
    f.put(
        "product.acceptance",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "acceptance");
          r.put("evidence_version_id", id(tenant, "evidence_version"));
          r.put("task_id", id(tenant, "task"));
          r.put("reviewer_id", id(tenant, "reviewer"));
          r.put("decision", "YEU_CAU_BO_SUNG");
          r.put("decided_at", OffsetDateTime.parse("2027-02-01T08:00:00Z"));
          r.put("criteria_results", jsonb("[]"));
          return r;
        });
    f.put(
        "product.evidence_version_file",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "evidence_version_file");
          r.put("evidence_version_id", id(tenant, "evidence_version"));
          r.put("task_id", id(tenant, "task"));
          r.put("ordinal", 1);
          r.put("object_id", id(tenant, "evidence_object"));
          r.put("object_version", 1);
          r.put("content_sha256", new byte[32]);
          return r;
        });
    f.put(
        "product.evidence_object",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "evidence_object"));
          r.put("created_by", id(tenant, "user"));
          r.put("updated_by", id(tenant, "user"));
          r.put("object_version", 1);
          return r;
        });
    f.put(
        "product.e2ee_key_wrap",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "e2ee_key_wrap"));
          r.put("created_by", id(tenant, "user"));
          r.put("updated_by", id(tenant, "user"));
          r.put("object_id", id(tenant, "evidence_version"));
          r.put("recipient_person_id", id(tenant, "reviewer"));
          r.put("recipient_key_fingerprint", new byte[] {1, 2, 3});
          r.put("wrap_alg", "ECDH-ES+A256KW");
          r.put("wrapped_cek", new byte[] {4, 5, 6});
          r.put("granted_by", id(tenant, "user"));
          return r;
        });
    return f;
  }

  // ---------------------------------------------------------------- ràng buộc toàn vẹn

  @Test
  void phienBanDaNopKhongSuaDuocNoiDung() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = submittedVersion(st, t, "false", "NULL");
      for (String change :
          List.of(
              "object_id = gen_random_uuid()",
              "content_sha256 = '\\x" + "cd".repeat(32) + "'",
              "size_bytes = 99",
              "version_no = 2")) {
        assertThatThrownBy(() -> st.execute(update(t, v, change)))
            .isInstanceOfSatisfying(
                SQLException.class,
                e -> {
                  assertThat(e.getSQLState()).isEqualTo("23000");
                  assertThat(e.getMessage()).contains("không được sửa nội dung");
                });
      }
      // Trường ngoài nội dung (ghi chú, trạng thái nghiệm thu) vẫn cập nhật được.
      st.execute(update(t, v, "note = 'Bổ sung ghi chú'"));
    }
  }

  @Test
  void phienBanNhapSuaDuocNoiDung() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = version(st, t, "'NHAP'", "false", "NULL", "'CHO_QUET'");
      st.execute(update(t, v, "object_id = gen_random_uuid(), content_sha256 = " + SHA));
    }
  }

  @Test
  void aiDienSanChuaXacNhanKhongNopDuoc() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = version(st, t, "'NHAP'", "true", "NULL", "'CHO_QUET'");
      assertThatThrownBy(() -> st.execute(update(t, v, submit())))
          .hasMessageContaining("ck_evidence_version__ai_confirm");
      st.execute(update(t, v, submit() + ", user_confirmed_at = now()"));
    }
  }

  @Test
  void nopThieuDoiTuongHoacNguoiNopBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = version(st, t, "'NHAP'", "false", "NULL", "'CHO_QUET'");
      assertThatThrownBy(() -> st.execute(update(t, v, "status = 'DA_NOP'")))
          .hasMessageContaining("ck_evidence_version__submitted");
      assertThatThrownBy(() -> st.execute(update(t, v, "content_sha256 = '\\x0102'")))
          .hasMessageContaining("ck_evidence_version__hash");
    }
  }

  @Test
  void tepChuaQuetSachKhongVaoNghiemThu() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = submittedVersion(st, t, "false", "NULL");
      assertThatThrownBy(() -> st.execute(update(t, v, "status = 'DANG_NGHIEM_THU'")))
          .hasMessageContaining("ck_evidence_version__clean");
      st.execute(update(t, v, "scan_status = 'NHIEM'"));
      assertThatThrownBy(() -> st.execute(update(t, v, "status = 'DANG_NGHIEM_THU'")))
          .hasMessageContaining("ck_evidence_version__clean");
      st.execute(update(t, v, "scan_status = 'SACH'"));
      st.execute(update(t, v, "status = 'DANG_NGHIEM_THU'"));
    }
  }

  @Test
  void khongHuyXacNhanDuoc() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = submittedVersion(st, t, "false", "NULL");
      st.execute(update(t, v, "scan_status = 'SACH', status = 'DA_XAC_NHAN', accepted_at = now()"));
      assertThatThrownBy(() -> st.execute(update(t, v, "status = 'DANG_NGHIEM_THU'")))
          .hasMessageContaining("Không được huỷ xác nhận");
    }
  }

  @Test
  void vaiTroUngDungKhongXoaDuoc() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      submittedVersion(st, t, "false", "NULL");
    }
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      for (String table : List.of("deliverable", "evidence", "evidence_version", "acceptance")) {
        assertThatThrownBy(() -> st.executeUpdate("DELETE FROM product." + table))
            .as(table)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      }
      assertThat(st.executeUpdate("UPDATE product.evidence_version SET note = 'Đã xem'"))
          .isEqualTo(1);
    }
  }

  @Test
  void nguoiNghiemThuTrungNguoiNopBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID submitter = UUID.randomUUID();
      UUID v = submittedVersion(st, t, "false", "'" + submitter + "'");
      assertThatThrownBy(() -> st.execute(acceptance(t, v, submitter, "'YEU_CAU_BO_SUNG'", "NULL")))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("23514");
                assertThat(e.getMessage()).contains("BR-07-06");
              });
      UUID reviewer = UUID.randomUUID();
      UUID a = UUID.randomUUID();
      st.execute(acceptance(a, t, v, reviewer, "'YEU_CAU_BO_SUNG'", "NULL"));
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE product.acceptance SET reviewer_id = '"
                          + submitter
                          + "' WHERE tenant_id = '"
                          + t
                          + "' AND id = '"
                          + a
                          + "'"))
          .hasMessageContaining("BR-07-06");
    }
  }

  @Test
  void mucChatLuongNgoaiNamMucBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID v = submittedVersion(st, t, "false", "NULL");
      UUID r = UUID.randomUUID();
      assertThatThrownBy(() -> st.execute(acceptance(t, v, r, "'DA_XAC_NHAN'", "0.6")))
          .hasMessageContaining("ck_acceptance__quality_level");
      assertThatThrownBy(() -> st.execute(acceptance(t, v, r, "'DA_XAC_NHAN'", "NULL")))
          .hasMessageContaining("ck_acceptance__quality_required");
      for (String level : List.of("1", "0.75", "0.5", "0.25", "0")) {
        st.execute(acceptance(t, v, r, "'DA_XAC_NHAN'", level));
      }
      assertThatThrownBy(
              () ->
                  st.execute(
                      acceptance(t, v, r, "'TU_CHOI'", "NULL")
                          .replace("decided_at)", "decided_at, revision_cause)")
                          .replace("now())", "now(), 'LY_DO_KHAC')")))
          .hasMessageContaining("ck_acceptance__revision_cause");
      assertThatThrownBy(
              () ->
                  st.execute(
                      acceptance(t, v, r, "'TU_CHOI'", "NULL")
                          .replace("decided_at)", "decided_at, comment_enc)")
                          .replace("now())", "now(), '\\x01')")))
          .hasMessageContaining("ck_acceptance__dek");
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
    Connection c = DriverManager.getConnection(jdbcUrl(), "svc_product", TEST_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    return c;
  }

  /** Sản phẩm, minh chứng và một phiên bản đã nộp (tệp chưa quét). */
  private static UUID submittedVersion(Statement st, UUID t, String aiPrefill, String submitter)
      throws SQLException {
    UUID v = version(st, t, "'NHAP'", aiPrefill, "NULL", "'CHO_QUET'");
    String by = submitter.equals("NULL") ? "'" + UUID.randomUUID() + "'" : submitter;
    st.execute(
        update(t, v, submit().replace("submitted_by = gen_random_uuid()", "submitted_by = " + by)));
    return v;
  }

  private static String submit() {
    return "status = 'DA_NOP', object_id = gen_random_uuid(), content_sha256 = "
        + SHA
        + ", size_bytes = 1024, submitted_by = gen_random_uuid(), submitted_at = now()";
  }

  private static UUID version(
      Statement st, UUID t, String status, String aiPrefill, String confirmedAt, String scan)
      throws SQLException {
    UUID deliverable = UUID.randomUUID();
    UUID evidence = UUID.randomUUID();
    UUID v = UUID.randomUUID();
    st.execute(
        "INSERT INTO product.deliverable (id, tenant_id, created_by, updated_by, period_id,"
            + " period_bucket, task_id, name, deliverable_type, unit, quantity_planned) VALUES ('"
            + deliverable
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
            + SAMPLE_PERIOD_BUCKET
            + ", gen_random_uuid(), 'Báo cáo chuyên đề', 'BAO_CAO', 'Báo cáo', 1)");
    st.execute(
        "INSERT INTO product.evidence (id, tenant_id, created_by, updated_by, period_id,"
            + " period_bucket, deliverable_id, task_id, title, source_type) VALUES ('"
            + evidence
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
            + SAMPLE_PERIOD_BUCKET
            + ", '"
            + deliverable
            + "', gen_random_uuid(), 'Báo cáo đã ký', 'TAI_LEN')");
    st.execute(
        "INSERT INTO product.evidence_version (id, tenant_id, created_by, updated_by, period_id,"
            + " period_bucket, evidence_id, task_id, version_no, status, source, ai_prefill,"
            + " user_confirmed_at, scan_status) VALUES ('"
            + v
            + "', '"
            + t
            + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
            + SAMPLE_PERIOD_BUCKET
            + ", '"
            + evidence
            + "', gen_random_uuid(), 1, "
            + status
            + ", 'TAI_LEN', "
            + aiPrefill
            + ", "
            + confirmedAt
            + ", "
            + scan
            + ")");
    return v;
  }

  private static String update(UUID t, UUID v, String set) {
    return "UPDATE product.evidence_version SET "
        + set
        + " WHERE tenant_id = '"
        + t
        + "' AND id = '"
        + v
        + "'";
  }

  private static String acceptance(UUID t, UUID v, UUID reviewer, String decision, String level) {
    return acceptance(UUID.randomUUID(), t, v, reviewer, decision, level);
  }

  private static String acceptance(
      UUID id, UUID t, UUID v, UUID reviewer, String decision, String level) {
    return "INSERT INTO product.acceptance (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, evidence_version_id, task_id, reviewer_id, decision, quality_level,"
        + " decided_at) VALUES ('"
        + id
        + "', '"
        + t
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + v
        + "', gen_random_uuid(), '"
        + reviewer
        + "', "
        + decision
        + ", "
        + level
        + ", now())";
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

  private static PGobject jsonb(String value) {
    try {
      PGobject o = new PGobject();
      o.setType("jsonb");
      o.setValue(value);
      return o;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID id(UUID tenant, String name) {
    return UUID.nameUUIDFromBytes((tenant + "/" + name).getBytes(StandardCharsets.UTF_8));
  }
}
