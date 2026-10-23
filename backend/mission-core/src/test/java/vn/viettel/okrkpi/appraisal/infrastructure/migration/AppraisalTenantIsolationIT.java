package vn.viettel.okrkpi.appraisal.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code appraisal} (CORE-04-01) ở CSDL pool: rò rỉ tenant (RLS) do lớp cơ sở sinh trên chín
 * bảng, cộng ràng buộc của TL-03: ảnh chụp điểm bất biến và chỉ một ảnh chụp hiện hành, mức xếp
 * loại không xoá, không sửa, đúng vai trò theo bước, tách vai trò thẩm định và quyết định, quyền
 * cột của {@code prospect_group.group_code}, dữ liệu năm chỉ ở phân vùng năm và chặn ghi khi hồ sơ
 * {@code DA_KHOA}.
 */
class AppraisalTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final int YEAR_BUCKET = 20270;
  private static final String SHA = "'\\x" + "ab".repeat(32) + "'";

  @Override
  protected List<String> schemas() {
    return List.of("appraisal");
  }

  /**
   * Phân vùng năm và hồ sơ năm cho hai tenant mẫu (bảng năm chỉ nhận period_bucket chia hết 10).
   */
  @BeforeAll
  void openYearPartitionAndProfiles() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String table :
          List.of("appraisal_profile", "annual_rating", "prospect_group", "prospect_board")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal." + table + "', 20270)");
      }
      for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
        st.execute(profileSql(id(tenant, "annual_profile"), tenant, YEAR_BUCKET, "'CHUA_LAP'"));
      }
    }
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "appraisal.appraisal_profile",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "appraisal_profile", SAMPLE_PERIOD_BUCKET);
          r.put("person_id", id(tenant, "person"));
          r.put("flow", "DANG");
          r.put("org_unit_id", id(tenant, "org"));
          r.put("assignment_id", id(tenant, "assignment"));
          r.put("job_title_id", id(tenant, "job"));
          r.put("policy_pack_version_id", id(tenant, "ppv"));
          r.put("appraiser_id", id(tenant, "appraiser"));
          r.put("decider_id", id(tenant, "decider"));
          return r;
        });
    f.put(
        "appraisal.general_criteria_result",
        (tenant, seed) -> {
          Map<String, Object> r = childRow(tenant, "general_criteria_result");
          r.put("stage", "TU_DANH_GIA");
          r.put("criteria_code", "CT01");
          r.put("compliance_ratio", new BigDecimal("0.9000"));
          r.put("level", "DAM_BAO");
          r.put("met", true);
          return r;
        });
    f.put(
        "appraisal.self_assessment",
        (tenant, seed) -> {
          Map<String, Object> r = childRow(tenant, "self_assessment");
          r.put("version_no", 1);
          r.put("form_code", "MAU_TU_DANH_GIA");
          r.put("form_version", "1.0.0");
          r.put("structured_data", jsonb("{}"));
          return r;
        });
    f.put(
        "appraisal.score_snapshot",
        (tenant, seed) -> {
          Map<String, Object> r = childRow(tenant, "score_snapshot");
          r.putAll(snapshotColumns(tenant, id(tenant, "score_result")));
          return r;
        });
    f.put(
        "appraisal.batch_decision",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "batch_decision", SAMPLE_PERIOD_BUCKET);
          r.put("decision_no", "123/QĐ-" + seed);
          r.put("decision_date", LocalDate.parse("2027-04-05"));
          r.put("decider_id", id(tenant, "decider"));
          r.put("flow", "DANG");
          r.put("profile_count", 1);
          r.put("profiles_hash", new byte[32]);
          return r;
        });
    f.put(
        "appraisal.rating_proposal",
        (tenant, seed) -> {
          Map<String, Object> r = childRow(tenant, "rating_proposal");
          r.put("created_by", id(tenant, "person"));
          r.put("level_kind", "TU_DE_XUAT");
          r.put("rating_code", "HTTNV");
          r.put("score_snapshot_id", id(tenant, "score_snapshot"));
          r.put("actor_id", id(tenant, "person"));
          r.put("actor_role", "staff");
          r.put("is_current", true);
          r.put("proposed_at", OffsetDateTime.parse("2027-04-01T08:00:00Z"));
          return r;
        });
    f.put(
        "appraisal.rating_explanation",
        (tenant, seed) -> {
          Map<String, Object> r = childRow(tenant, "rating_explanation");
          r.put("rating_proposal_id", id(tenant, "rating_proposal"));
          r.put("explanation_type", "HA_MUC");
          return r;
        });
    f.put(
        "appraisal.annual_rating",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "annual_rating", YEAR_BUCKET);
          r.put("person_id", id(tenant, "person"));
          r.put("flow", "DANG");
          r.put("profile_id", id(tenant, "annual_profile"));
          return r;
        });
    f.put(
        "appraisal.prospect_group",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "prospect_group", YEAR_BUCKET);
          r.put("person_id", id(tenant, "person"));
          r.put("group_code", "NHOM_1");
          return r;
        });
    // Danh sách phân nhóm của đơn vị (CORE-04-12), chỉ ở phân vùng năm.
    f.put(
        "appraisal.prospect_board",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "prospect_board", YEAR_BUCKET);
          r.put("org_unit_id", id(tenant, "org"));
          return r;
        });
    return f;
  }

  // ---------------------------------------------------------------- ảnh chụp điểm

  @Test
  void anhChupDiemBatBienVaChiMotAnhChupHienHanh() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(profileSql(profile, t, SAMPLE_PERIOD_BUCKET, "'TU_DANH_GIA'"));
      st.execute(snapshotSql(s1, t, profile));
      assertThatThrownBy(() -> st.execute(snapshotSql(UUID.randomUUID(), t, profile)))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("23505");
                assertThat(e.getMessage()).contains("duplicate key", "profile_id");
              });
    }
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      assertThatThrownBy(
              () -> st.executeUpdate("UPDATE appraisal.score_snapshot SET total_score = 99"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM appraisal.score_snapshot"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      // Thay ảnh chụp: đặt superseded_by_id của ảnh chụp cũ rồi chèn ảnh chụp mới (TL-03).
      assertThat(
              st.executeUpdate(
                  "UPDATE appraisal.score_snapshot SET superseded_by_id = '"
                      + s2
                      + "' WHERE id = '"
                      + s1
                      + "'"))
          .isEqualTo(1);
      st.execute(snapshotSql(s2, t, profile));
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "UPDATE appraisal.score_snapshot SET superseded_by_id = gen_random_uuid()"
                          + " WHERE id = '"
                          + s1
                          + "'"))
          .hasMessageContaining("đã bị thay thế");
    }
  }

  @Test
  void luongNhaNuocBatBuocCoDiemTieuChiChungVaKpi() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(profileSql(profile, t, SAMPLE_PERIOD_BUCKET, "'TU_DANH_GIA'"));
      assertThatThrownBy(
              () ->
                  st.execute(
                      snapshotSql(UUID.randomUUID(), t, profile)
                          .replace("'DANG', 'TAM_TINH'", "'NHA_NUOC', 'TAM_TINH'")))
          .hasMessageContaining("ck_score_snapshot__nn");
      assertThatThrownBy(
              () ->
                  st.execute(snapshotSql(UUID.randomUUID(), t, profile).replace(SHA, "'\\x0102'")))
          .hasMessageContaining("ck_score_snapshot__hashes");
    }
  }

  // ---------------------------------------------------------------- mức xếp loại

  @Test
  void mucXepLoaiKhongXoaKhongSuaVaDungVaiTro() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    UUID staff = UUID.randomUUID();
    UUID p1 = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(profileSql(profile, t, SAMPLE_PERIOD_BUCKET, "'TU_DANH_GIA'"));
      st.execute(snapshotSql(snapshot, t, profile));
      st.execute(proposalSql(p1, t, profile, snapshot, "'TU_DE_XUAT'", "'staff'", staff, staff));
      assertThatThrownBy(
              () ->
                  st.execute(
                      proposalSql(
                          UUID.randomUUID(),
                          t,
                          profile,
                          snapshot,
                          "'TU_DE_XUAT'",
                          "'staff'",
                          staff,
                          staff)))
          .hasMessageContaining("duplicate key")
          .hasMessageContaining("level_kind");
      assertThatThrownBy(
              () ->
                  st.execute(
                      proposalSql(
                          UUID.randomUUID(),
                          t,
                          profile,
                          snapshot,
                          "'QUYET_DINH'",
                          "'staff'",
                          staff,
                          staff)))
          .hasMessageContaining("ck_rating_proposal__role");
      UUID decider = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  st.execute(
                      proposalSql(
                          UUID.randomUUID(),
                          t,
                          profile,
                          snapshot,
                          "'QUYET_DINH'",
                          "'decider'",
                          decider,
                          UUID.randomUUID())))
          .hasMessageContaining("ck_rating_proposal__actor_is_human");
    }
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM appraisal.rating_proposal"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "UPDATE appraisal.rating_proposal SET rating_code = 'HTXSNV' WHERE id = '"
                          + p1
                          + "'"))
          .hasMessageContaining("không được sửa");
      assertThat(
              st.executeUpdate(
                  "UPDATE appraisal.rating_proposal SET is_current = false WHERE id = '"
                      + p1
                      + "'"))
          .isEqualTo(1);
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "UPDATE appraisal.rating_proposal SET is_current = true WHERE id = '"
                          + p1
                          + "'"))
          .hasMessageContaining("Không khôi phục");
    }
  }

  @Test
  void tachVaiTroThamDinhVaQuyetDinh() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      String same = "'" + UUID.randomUUID() + "'";
      assertThatThrownBy(
              () ->
                  st.execute(
                      profileSql(UUID.randomUUID(), t, SAMPLE_PERIOD_BUCKET, "'CHUA_LAP'")
                          .replace(
                              "gen_random_uuid(), gen_random_uuid())", same + ", " + same + ")")))
          .hasMessageContaining("ck_appraisal_profile__separation");
    }
  }

  // ---------------------------------------------------------------- dữ liệu năm và quyền cột

  @Test
  void duLieuNamChiNamOPhanVungNam() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      UUID t = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  st.execute(
                      "INSERT INTO appraisal.prospect_group (id, tenant_id, created_by, updated_by,"
                          + " period_id, period_bucket, person_id, group_code) VALUES"
                          + " (gen_random_uuid(), '"
                          + t
                          + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
                          + SAMPLE_PERIOD_BUCKET
                          + ", gen_random_uuid(), 'NHOM_1')"))
          .hasMessageContaining("ck_prospect_group__bucket");
    }
  }

  @Test
  void vaiTroChiDocKhongDocDuocNhomTrienVong() throws SQLException {
    try (Connection ro = DriverManager.getConnection(jdbcUrl(), "ro_appraisal", TEST_PASSWORD);
        Statement st = ro.createStatement()) {
      st.execute("SET app.tenant_id = '" + TENANT_A + "'");
      assertThatThrownBy(() -> st.executeQuery("SELECT group_code FROM appraisal.prospect_group"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(
              () -> st.executeQuery("SELECT suggested_group FROM appraisal.prospect_group"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      try (ResultSet rs = st.executeQuery("SELECT person_id FROM appraisal.prospect_group")) {
        assertThat(rs.next()).isTrue();
      }
    }
    try (Connection svc = svc(TENANT_A);
        Statement st = svc.createStatement();
        ResultSet rs = st.executeQuery("SELECT group_code FROM appraisal.prospect_group")) {
      assertThat(rs.next()).isTrue();
    }
  }

  // ---------------------------------------------------------------- hồ sơ DA_KHOA

  @Test
  void hoSoDaKhoaChanGhiTruKhiMoLaiHauKiem() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(profileSql(profile, t, SAMPLE_PERIOD_BUCKET, "'DA_KY_SO'"));
      st.execute(
          "UPDATE appraisal.appraisal_profile SET status = 'DA_KHOA', locked_at = now() WHERE id = '"
              + profile
              + "'");
    }
    try (Connection svc = svc(t);
        Statement st = svc.createStatement()) {
      assertThatThrownBy(() -> st.execute(snapshotSql(UUID.randomUUID(), t, profile)))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("55000");
                assertThat(e.getMessage()).contains("đã khoá");
              });
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "UPDATE appraisal.appraisal_profile SET status = 'KHIEU_NAI' WHERE id = '"
                          + profile
                          + "'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
      svc.setAutoCommit(false);
      st.execute("SET LOCAL app.reopen_request_id = '" + UUID.randomUUID() + "'");
      st.execute(
          snapshotSql(UUID.randomUUID(), t, profile).replace("'TAM_TINH'", "'TINH_LAI_HAU_KIEM'"));
      svc.commit();
      // Hết giao dịch, biến phiên trở về rỗng: lại bị chặn.
      assertThatThrownBy(
              () ->
                  st.execute(
                      "INSERT INTO appraisal.general_criteria_result (id, tenant_id, created_by,"
                          + " updated_by, period_id, period_bucket, profile_id, stage, criteria_code,"
                          + " compliance_ratio, level, met) VALUES (gen_random_uuid(), '"
                          + t
                          + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
                          + SAMPLE_PERIOD_BUCKET
                          + ", '"
                          + profile
                          + "', 'THAM_DINH', 'CT01', 1, 'DAM_BAO', true)"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
      svc.rollback();
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
    Connection c = DriverManager.getConnection(jdbcUrl(), "svc_appraisal", TEST_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    return c;
  }

  private static String profileSql(UUID id, UUID t, int bucket, String status) {
    return "INSERT INTO appraisal.appraisal_profile (id, tenant_id, created_by, updated_by,"
        + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
        + " policy_pack_version_id, status, appraiser_id, decider_id) VALUES ('"
        + id
        + "', '"
        + t
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + bucket
        + ", gen_random_uuid(), 'DANG', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
        + " gen_random_uuid(), "
        + status
        + ", gen_random_uuid(), gen_random_uuid())";
  }

  private static String snapshotSql(UUID id, UUID t, UUID profile) {
    return "INSERT INTO appraisal.score_snapshot (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, profile_id, flow, snapshot_kind, score_result_id, policy_pack_version_id,"
        + " policy_content_hash, policy_version, input_hash, total_score, auto_rating_code,"
        + " rule_checks, group_scores_json, computed_at, snapshot_hash) VALUES ('"
        + id
        + "', '"
        + t
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + profile
        + "', 'DANG', 'TAM_TINH', gen_random_uuid(), gen_random_uuid(), "
        + SHA
        + ", 'KHOI_DANG v2.0', "
        + SHA
        + ", 82.5, 'HTTNV', '[]', '{}', now(), "
        + SHA
        + ")";
  }

  private static String proposalSql(
      UUID id,
      UUID t,
      UUID profile,
      UUID snapshot,
      String levelKind,
      String role,
      UUID actor,
      UUID createdBy) {
    return "INSERT INTO appraisal.rating_proposal (id, tenant_id, created_by, updated_by, period_id,"
        + " period_bucket, profile_id, level_kind, rating_code, score_snapshot_id, actor_id,"
        + " actor_role, is_current, proposed_at) VALUES ('"
        + id
        + "', '"
        + t
        + "', '"
        + createdBy
        + "', gen_random_uuid(), gen_random_uuid(), "
        + SAMPLE_PERIOD_BUCKET
        + ", '"
        + profile
        + "', "
        + levelKind
        + ", 'HTTNV', '"
        + snapshot
        + "', '"
        + actor
        + "', "
        + role
        + ", true, now())";
  }

  private static Map<String, Object> snapshotColumns(UUID tenant, UUID scoreResult) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("flow", "DANG");
    r.put("snapshot_kind", "TAM_TINH");
    r.put("score_result_id", scoreResult);
    r.put("policy_pack_version_id", id(tenant, "ppv"));
    r.put("policy_content_hash", new byte[32]);
    r.put("policy_version", "KHOI_DANG v2.0");
    r.put("input_hash", new byte[32]);
    r.put("total_score", new BigDecimal("82.500"));
    r.put("auto_rating_code", "HTTNV");
    r.put("rule_checks", jsonb("[]"));
    r.put("group_scores_json", jsonb("{}"));
    r.put("computed_at", OffsetDateTime.parse("2027-04-01T08:00:00Z"));
    r.put("snapshot_hash", new byte[32]);
    return r;
  }

  private static Map<String, Object> childRow(UUID tenant, String table) {
    Map<String, Object> r = periodRow(tenant, table, SAMPLE_PERIOD_BUCKET);
    r.put("profile_id", id(tenant, "appraisal_profile"));
    return r;
  }

  private static Map<String, Object> periodRow(UUID tenant, String table, int bucket) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    r.put("period_bucket", bucket);
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
