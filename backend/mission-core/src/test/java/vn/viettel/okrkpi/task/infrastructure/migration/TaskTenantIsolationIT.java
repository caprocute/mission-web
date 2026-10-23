package vn.viettel.okrkpi.task.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Kiểm thử schema {@code task} phần danh mục và kế hoạch (CORE-02-01) ở CSDL pool (16 phân vùng con
 * băm theo {@code tenant_id}): rò rỉ tenant (RLS) do lớp cơ sở sinh, cộng các ràng buộc nghiệp vụ
 * của TL-03. Ba bảng theo kỳ có ràng buộc chéo cột nên dùng dòng mẫu dựng tay với id tất định để
 * khoá ngoại khớp nhau.
 */
class TaskTenantIsolationIT extends TenantIsolationCatalogTest {

  @Override
  protected List<String> schemas() {
    return List.of("task");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "task.plan",
        (tenant, seed) -> {
          Map<String, Object> r = common(tenant, "plan");
          r.put("period_id", id(tenant, "period"));
          r.put("plan_type", "TAP_THE");
          r.put("owner_unit_id", id(tenant, "unit"));
          return r;
        });
    f.put(
        "task.plan_version",
        (tenant, seed) -> {
          Map<String, Object> r = common(tenant, "plan_version");
          r.put("period_id", id(tenant, "period"));
          r.put("plan_id", id(tenant, "plan"));
          r.put("version_no", 1);
          r.put("status", "NHAP");
          return r;
        });
    f.put(
        "task.plan_item",
        (tenant, seed) -> {
          Map<String, Object> r = common(tenant, "plan_item");
          r.put("period_id", id(tenant, "period"));
          r.put("plan_version_id", id(tenant, "plan_version"));
          r.put("item_no", 1);
          r.put("title", "Rà soát hồ sơ");
          r.put("task_group", "THUONG_XUYEN");
          r.put("lead_unit_id", id(tenant, "unit"));
          r.put("planned_quantity", new BigDecimal("1.0000"));
          r.put("unit", "Hồ sơ");
          r.put("due_date", LocalDate.of(2027, 3, 31));
          return r;
        });
    f.put(
        "task.task",
        (tenant, seed) -> {
          Map<String, Object> r = common(tenant, "task");
          r.put("period_id", id(tenant, "period"));
          r.put("code", "NV-0001");
          r.put("title", "Rà soát hồ sơ");
          r.put("task_group", "THUONG_XUYEN");
          r.put("source", "KE_HOACH");
          r.put("assigner_id", id(tenant, "user"));
          r.put("due_date", LocalDate.of(2027, 3, 31));
          r.put("unit", "Hồ sơ");
          r.put("dup_fingerprint", new byte[] {1, 2, 3});
          r.put("state_machine_version", "task-v1");
          return r;
        });
    f.put(
        "task.task_assignee",
        (tenant, seed) -> {
          Map<String, Object> r = satellite(tenant, "task_assignee");
          r.put("assignee_type", "CA_NHAN");
          r.put("person_id", id(tenant, "user"));
          r.put("role", "CHU_TRI");
          r.put("valid_from", LocalDate.of(2027, 1, 1));
          return r;
        });
    f.put(
        "task.task_progress",
        (tenant, seed) -> {
          Map<String, Object> r = satellite(tenant, "task_progress");
          r.put("reported_at", OffsetDateTime.parse("2027-02-01T08:00:00Z"));
          r.put("progress_pct", new BigDecimal("0.5000"));
          r.put("channel", "WEB");
          r.put("idempotency_key", "k-" + tenant);
          return r;
        });
    f.put(
        "task.task_blocker",
        (tenant, seed) -> {
          Map<String, Object> r = satellite(tenant, "task_blocker");
          r.put("cause_category", "PHOI_HOP");
          r.put("cause_detail", "Chờ đơn vị phối hợp cung cấp số liệu");
          r.put("impact_level", "TRUNG_BINH");
          return r;
        });
    f.put(
        "task.task_change_request",
        (tenant, seed) -> {
          Map<String, Object> r = satellite(tenant, "task_change_request");
          r.put("request_type", "GIA_HAN");
          r.put("proposed_due_date", LocalDate.of(2027, 4, 30));
          r.put("reason", "Chờ số liệu");
          return r;
        });
    f.put(
        "task.task_source_doc",
        (tenant, seed) -> {
          Map<String, Object> r = satellite(tenant, "task_source_doc");
          r.put("source_system", "VOFFICE");
          r.put("external_doc_id", "VB-" + tenant);
          r.put("paragraph_no", 1);
          return r;
        });
    f.put(
        "task.task_state_log",
        (tenant, seed) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("id", id(tenant, "task_state_log"));
          r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
          r.put("period_id", id(tenant, "period"));
          r.put("task_id", id(tenant, "task"));
          r.put("to_status", "DA_GIAO");
          r.put("transition", "GIAO");
          r.put("actor_id", id(tenant, "user"));
          r.put("before_after", jsonb("{}"));
          r.put("state_machine_version", "task-v1");
          return r;
        });
    f.put(
        "task.task_secret_content",
        (tenant, seed) -> {
          Map<String, Object> r = satellite(tenant, "task_secret_content");
          r.put("ciphertext", new byte[] {1, 2, 3, (byte) seed});
          r.put("nonce", new byte[12]);
          r.put("aad_hash", new byte[32]);
          r.put("content_sha256", new byte[32]);
          r.put("sender_key_fingerprint", new byte[32]);
          return r;
        });
    f.put(
        "task.e2ee_key_wrap",
        (tenant, seed) -> {
          Map<String, Object> r = common(tenant, "e2ee_key_wrap");
          r.remove("period_bucket");
          r.put("object_id", id(tenant, "task"));
          r.put("recipient_person_id", id(tenant, "user"));
          r.put("recipient_key_fingerprint", new byte[32]);
          r.put("wrap_alg", "RSA-OAEP-256");
          r.put("wrapped_cek", new byte[] {4, 5, (byte) seed});
          r.put("granted_by", id(tenant, "user"));
          return r;
        });
    return f;
  }

  private static Map<String, Object> satellite(UUID tenant, String table) {
    Map<String, Object> r = common(tenant, table);
    r.put("period_id", id(tenant, "period"));
    r.put("task_id", id(tenant, "task"));
    return r;
  }

  @Test
  void haiPhienBanDaDuyetHienHanhCuaMotKeHoachBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID plan = insertPlan(st, t);
      st.execute(version(t, plan, 1, "'DA_DUYET'", "true", "NULL"));
      assertThatThrownBy(
              () -> st.execute(version(t, plan, 2, "'DA_DUYET'", "true", "'Điều chỉnh'")))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
      // Phiên bản cũ thôi hiện hành trước thì phiên bản mới mới được đặt hiện hành.
      st.execute("UPDATE task.plan_version SET is_current = false WHERE tenant_id = '" + t + "'");
      st.execute(version(t, plan, 2, "'DA_DUYET'", "true", "'Điều chỉnh'"));
    }
  }

  @Test
  void phienBanDaDuyetVaDongCuaNoBatBien() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID plan = insertPlan(st, t);
      UUID v = UUID.randomUUID();
      st.execute(version(v, t, plan, 1, "'CHO_DUYET'", "false", "NULL"));
      st.execute(item(t, v, "'" + UUID.randomUUID() + "'", "NULL"));
      String where = " WHERE id = '" + v + "'";
      st.execute("UPDATE task.plan_version SET status = 'DA_DUYET', is_current = true" + where);
      // BR-04-09: chỉ được bỏ cờ hiện hành; nội dung, xoá và mọi thay đổi dòng bị chặn.
      for (String sql :
          List.of(
              "UPDATE task.plan_version SET review_comment = 'sửa'" + where,
              "UPDATE task.plan_version SET status = 'TRA_LAI', is_current = false" + where,
              "DELETE FROM task.plan_version" + where,
              item(t, v, "'" + UUID.randomUUID() + "'", "NULL"),
              "UPDATE task.plan_item SET title = 'Khác' WHERE plan_version_id = '" + v + "'",
              "DELETE FROM task.plan_item WHERE plan_version_id = '" + v + "'")) {
        assertThatThrownBy(() -> st.execute(sql))
            .as(sql)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23000"));
      }
      st.execute(
          "UPDATE task.plan_version SET is_current = false, row_version = row_version + 1,"
              + " updated_at = now()"
              + where);
    }
  }

  @Test
  void phienBanThuHaiThieuLyDoBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID plan = insertPlan(st, t);
      st.execute(version(t, plan, 1, "'NHAP'", "false", "NULL"));
      assertThatThrownBy(() -> st.execute(version(t, plan, 2, "'NHAP'", "false", "NULL")))
          .hasMessageContaining("ck_plan_version__change_reason");
      assertThatThrownBy(() -> st.execute(version(t, plan, 2, "'NHAP'", "false", "'  '")))
          .hasMessageContaining("ck_plan_version__change_reason");
    }
  }

  @Test
  void dongKeHoachCoHaiChuTriBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      UUID plan = insertPlan(st, t);
      UUID v = UUID.randomUUID();
      st.execute(version(v, t, plan, 1, "'NHAP'", "false", "NULL"));
      String both = item(t, v, "'" + UUID.randomUUID() + "'", "'" + UUID.randomUUID() + "'");
      assertThatThrownBy(() -> st.execute(both)).hasMessageContaining("ck_plan_item__one_lead");
      String none = item(t, v, "NULL", "NULL");
      assertThatThrownBy(() -> st.execute(none)).hasMessageContaining("ck_plan_item__one_lead");
      st.execute(item(t, v, "'" + UUID.randomUUID() + "'", "NULL"));
    }
  }

  @Test
  void mucDanhMucDoKho6VaThamChieuDanhMucTenantKhacBiChan() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID a = UUID.randomUUID();
      UUID b = UUID.randomUUID();
      UUID cat = UUID.randomUUID();
      st.execute(
          "INSERT INTO task.work_catalog (id, tenant_id, created_by, updated_by, code, name, scope,"
              + " version, source, version_no) VALUES ('"
              + cat
              + "', '"
              + a
              + "', '"
              + a
              + "', '"
              + a
              + "', 'DM-01', 'Danh mục chuyên viên', 'VI_TRI', 1, 'TU_TAO', 1)");
      assertThatThrownBy(() -> st.execute(catalogItem(a, cat, 6)))
          .hasMessageContaining("ck_catalog_item__difficulty");
      assertThatThrownBy(() -> st.execute(catalogItem(b, cat, 3)))
          .hasMessageContaining("fk_catalog_item__work_catalog");
      st.execute(catalogItem(a, cat, 5));
      assertThat(count(st, "task.catalog_item", a)).isEqualTo(1);
    }
  }

  private Connection admin() throws SQLException {
    Connection c =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ensurePeriodPartitionsCreated(jdbcUrl(), c, schemas());
    return c;
  }

  private static UUID insertPlan(Statement st, UUID t) throws SQLException {
    UUID plan = UUID.randomUUID();
    st.execute(
        "INSERT INTO task.plan (id, tenant_id, created_by, updated_by, period_id, period_bucket,"
            + " plan_type, owner_unit_id) VALUES ('"
            + plan
            + "', '"
            + t
            + "', '"
            + t
            + "', '"
            + t
            + "', '"
            + t
            + "', "
            + SAMPLE_PERIOD_BUCKET
            + ", 'TAP_THE', '"
            + UUID.randomUUID()
            + "')");
    return plan;
  }

  private static String version(
      UUID t, UUID plan, int no, String status, String current, String reason) {
    return version(UUID.randomUUID(), t, plan, no, status, current, reason);
  }

  private static String version(
      UUID id, UUID t, UUID plan, int no, String status, String current, String reason) {
    return "INSERT INTO task.plan_version (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, plan_id, version_no, status, is_current, change_reason, approved_by,"
        + " approved_at) VALUES ('"
        + id
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + plan
        + "', "
        + no
        + ", "
        + status
        + ", "
        + current
        + ", "
        + reason
        + ", '"
        + t
        + "', now())";
  }

  private static String item(UUID t, UUID version, String leadUnit, String leadPerson) {
    return "INSERT INTO task.plan_item (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, plan_version_id, item_no, title, task_group, lead_unit_id, lead_person_id,"
        + " planned_quantity, unit, due_date) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + version
        + "', 1, 'Rà soát hồ sơ', 'THEN_CHOT', "
        + leadUnit
        + ", "
        + leadPerson
        + ", 2, 'Hồ sơ', DATE '2027-03-31')";
  }

  private static String catalogItem(UUID t, UUID catalog, int difficulty) {
    return "INSERT INTO task.catalog_item (id, tenant_id, created_by, updated_by, work_catalog_id,"
        + " code, name, product_name, unit, difficulty, standard_score, quality_standard,"
        + " task_group) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + t
        + "', '"
        + catalog
        + "', 'CV-"
        + difficulty
        + "', 'Thẩm định hồ sơ', 'Báo cáo thẩm định', 'Hồ sơ', "
        + difficulty
        + ", "
        + new BigDecimal("12.500")
        + ", 'Đúng hạn, đủ nội dung', 'THUONG_XUYEN')";
  }

  private static long count(Statement st, String table, UUID t) throws SQLException {
    try (var rs =
        st.executeQuery("SELECT count(*) FROM " + table + " WHERE tenant_id = '" + t + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Map<String, Object> common(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
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
