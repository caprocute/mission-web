package vn.viettel.okrkpi.appraisal.infrastructure.migration;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code appraisal} (CORE-04-01) ở CSDL pool: rò rỉ tenant (RLS) do lớp cơ sở sinh trên chín
 * bảng, cộng các bảo đảm của TL-03: ảnh chụp điểm bất biến trừ cột thay thế, một ảnh chụp hiện
 * hành, không xoá mức đề xuất, quyền cột của nhóm triển vọng, chặn ghi hồ sơ đã khoá. Mọi dòng mẫu
 * ở kỳ năm 20270 để xếp loại năm và nhóm triển vọng ({@code period_bucket % 10 = 0}) dùng chung hồ
 * sơ.
 */
class AppraisalIntegrityIT extends TenantIsolationCatalogTest {

  private static final int YEAR_BUCKET = 20270;
  private static final String HASH = "'\\x" + "ab".repeat(32) + "'";

  @Override
  protected List<String> schemas() {
    return List.of("appraisal");
  }

  @BeforeAll
  void moPhanVungKyNam() throws SQLException {
    try (Connection c = DriverManager.getConnection(jdbcUrl(), "svc_appraisal", TEST_PASSWORD);
        Statement st = c.createStatement()) {
      for (String table :
          List.of(
              "appraisal_profile",
              "general_criteria_result",
              "self_assessment",
              "score_snapshot",
              "batch_decision",
              "rating_proposal",
              "rating_explanation",
              "annual_rating",
              "prospect_group")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal."
                + table
                + "', "
                + YEAR_BUCKET
                + ")");
      }
    }
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "appraisal.appraisal_profile",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "profile");
          r.put("person_id", id(tenant, "person"));
          r.put("flow", "NHA_NUOC");
          r.put("org_unit_id", id(tenant, "unit"));
          r.put("assignment_id", id(tenant, "assignment"));
          r.put("job_title_id", id(tenant, "job"));
          r.put("policy_pack_version_id", id(tenant, "pack"));
          return r;
        });
    f.put(
        "appraisal.general_criteria_result",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "gcr");
          r.put("profile_id", id(tenant, "profile"));
          r.put("stage", "TU_DANH_GIA");
          r.put("criteria_code", "TC01");
          r.put("compliance_ratio", new BigDecimal("0.9000"));
          r.put("level", "DAM_BAO");
          r.put("met", true);
          return r;
        });
    f.put(
        "appraisal.self_assessment",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "self");
          r.put("profile_id", id(tenant, "profile"));
          r.put("version_no", 1);
          r.put("form_code", "MAU_TU_DANH_GIA");
          r.put("form_version", "1");
          r.put("structured_data", jsonb("{}"));
          return r;
        });
    f.put(
        "appraisal.score_snapshot",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "snapshot");
          r.putAll(snapshotColumns(tenant, id(tenant, "result")));
          return r;
        });
    f.put(
        "appraisal.batch_decision",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "batch");
          r.put("decision_no", "QD-01");
          r.put("decision_date", LocalDate.of(2027, 1, 15));
          r.put("decider_id", id(tenant, "decider"));
          r.put("flow", "NHA_NUOC");
          r.put("profile_count", 1);
          r.put("profiles_hash", new byte[32]);
          return r;
        });
    f.put(
        "appraisal.rating_proposal",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "proposal");
          r.put("profile_id", id(tenant, "profile"));
          r.put("level_kind", "TU_DE_XUAT");
          r.put("rating_code", "HTNV");
          r.put("score_snapshot_id", id(tenant, "snapshot"));
          r.put("actor_id", id(tenant, "user"));
          r.put("actor_role", "staff");
          r.put("is_current", true);
          r.put("proposed_at", OffsetDateTime.parse("2027-01-10T08:00:00Z"));
          return r;
        });
    f.put(
        "appraisal.rating_explanation",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "explanation");
          r.put("rating_proposal_id", id(tenant, "proposal"));
          r.put("profile_id", id(tenant, "profile"));
          r.put("explanation_type", "KHAC_MUC_TRUOC");
          return r;
        });
    f.put(
        "appraisal.annual_rating",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "annual");
          r.put("person_id", id(tenant, "person"));
          r.put("flow", "NHA_NUOC");
          r.put("profile_id", id(tenant, "profile"));
          r.put("quarter_ratings", new String[] {"HTNV", "HTTNV"});
          return r;
        });
    f.put(
        "appraisal.prospect_group",
        (tenant, seed) -> {
          Map<String, Object> r = row(tenant, "prospect");
          r.put("person_id", id(tenant, "person"));
          r.put("group_code", "NHOM_2");
          return r;
        });
    return f;
  }

  // ---------------------------------------------------------------- ảnh chụp điểm

  @Test
  void anhChupDiemKhongSuaDuocCotCam() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = profile(t, "'CHO_THAM_DINH'");
    UUID snap = snapshot(t, profile);
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      for (String change :
          List.of("total_score = 90", "auto_rating_code = 'HTXSNV'", "input_hash = " + HASH)) {
        assertThatThrownBy(
                () ->
                    st.execute(
                        "UPDATE appraisal.score_snapshot SET "
                            + change
                            + " WHERE id = '"
                            + snap
                            + "'"))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      }
      assertThatThrownBy(
              () -> st.execute("DELETE FROM appraisal.score_snapshot WHERE id = '" + snap + "'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
  }

  @Test
  void haiAnhChupHienHanhCungHoSoBiChan() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = profile(t, "'CHO_THAM_DINH'");
    snapshot(t, profile);
    assertThatThrownBy(() -> snapshot(t, profile))
        .isInstanceOfSatisfying(
            SQLException.class,
            e -> {
              assertThat(e.getSQLState()).isEqualTo("23505");
              assertThat(e.getMessage()).contains("ix_score_snapshot__current");
            });
  }

  @Test
  void thayAnhChupHaiBuocVaChiThayMotLan() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = profile(t, "'CHO_THAM_DINH'");
    UUID old = snapshot(t, profile);
    UUID moi = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      st.execute(
          "UPDATE appraisal.score_snapshot SET superseded_by_id = '"
              + moi
              + "', row_version = row_version + 1 WHERE id = '"
              + old
              + "'");
      st.execute(insertSnapshot(t, moi, profile));
      c.commit();
      c.setAutoCommit(true);
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE appraisal.score_snapshot SET superseded_by_id = gen_random_uuid()"
                          + " WHERE id = '"
                          + old
                          + "'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getMessage()).contains("đã bị thay thế"));
    }
  }

  // ---------------------------------------------------------------- mức đề xuất

  @Test
  void khongXoaDuocMucDeXuat() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = profile(t, "'TU_DANH_GIA'");
    UUID snap = snapshot(t, profile);
    UUID proposal = proposal(t, profile, snap, "'TU_DE_XUAT'", "'staff'");
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.execute("DELETE FROM appraisal.rating_proposal WHERE id = '" + proposal + "'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
  }

  @Test
  void mucQuyetDinhChiDoNguoiCoVaiTroQuyetDinh() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = profile(t, "'CHO_QUYET_DINH'");
    UUID snap = snapshot(t, profile);
    assertThatThrownBy(() -> proposal(t, profile, snap, "'QUYET_DINH'", "'manager'"))
        .isInstanceOfSatisfying(
            SQLException.class,
            e -> assertThat(e.getMessage()).contains("ck_rating_proposal__role"));
  }

  // ---------------------------------------------------------------- nhóm triển vọng

  @Test
  void vaiTroChiDocKhongDocDuocNhomTrienVong() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO appraisal.prospect_group (id, created_by, updated_by, period_id,"
              + " period_bucket, person_id, group_code) VALUES (gen_random_uuid(), '"
              + t
              + "', '"
              + t
              + "', gen_random_uuid(), "
              + YEAR_BUCKET
              + ", gen_random_uuid(), 'NHOM_1')");
      st.executeQuery("SELECT group_code FROM appraisal.prospect_group").close();
    }
    try (Connection c = DriverManager.getConnection(jdbcUrl(), "ro_appraisal", TEST_PASSWORD);
        Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + t + "'");
      st.executeQuery("SELECT person_id, rank_in_group FROM appraisal.prospect_group").close();
      for (String col : List.of("group_code", "suggested_group", "*")) {
        assertThatThrownBy(
                () -> st.executeQuery("SELECT " + col + " FROM appraisal.prospect_group"))
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      }
    }
  }

  @Test
  void quyNamPhaiLaKyNam() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.execute(
                      "INSERT INTO appraisal.prospect_group (id, created_by, updated_by, period_id,"
                          + " period_bucket, person_id, group_code) VALUES (gen_random_uuid(), '"
                          + t
                          + "', '"
                          + t
                          + "', gen_random_uuid(), "
                          + SAMPLE_PERIOD_BUCKET
                          + ", gen_random_uuid(), 'NHOM_1')"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> assertThat(e.getMessage()).contains("ck_prospect_group__year"));
    }
  }

  // ---------------------------------------------------------------- hồ sơ đã khoá

  @Test
  void hoSoDaKhoaChanGhiBangConTruKhiMoLaiHauKiem() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID profile = profile(t, "'DA_KY_SO'");
    UUID snap = snapshot(t, profile);
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(
          "UPDATE appraisal.appraisal_profile SET status = 'DA_KHOA', locked_at = now()"
              + " WHERE id = '"
              + profile
              + "'");
      assertThatThrownBy(() -> proposal(t, profile, snap, "'THAM_DINH'", "'appraiser'"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("55000");
                assertThat(e.getMessage()).contains("đã khoá");
              });
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE appraisal.appraisal_profile SET final_rating_code = 'HTNV'"
                          + " WHERE id = '"
                          + profile
                          + "'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));

      c.setAutoCommit(false);
      st.execute("SET LOCAL app.reopen_request_id = '" + UUID.randomUUID() + "'");
      st.execute(insertProposal(t, UUID.randomUUID(), profile, snap, "'THAM_DINH'", "'appraiser'"));
      c.commit();
      c.setAutoCommit(true);
      // Sau giao dịch mở lại, biến phiên về chuỗi rỗng: lại bị chặn.
      assertThatThrownBy(() -> proposal(t, profile, snap, "'DE_XUAT'", "'manager'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55000"));
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Connection svc(UUID tenant) throws SQLException {
    Connection c = DriverManager.getConnection(jdbcUrl(), "svc_appraisal", TEST_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    return c;
  }

  private UUID profile(UUID t, String status) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
              + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
              + " policy_pack_version_id, status) VALUES ('"
              + id
              + "', '"
              + t
              + "', '"
              + t
              + "', gen_random_uuid(), "
              + YEAR_BUCKET
              + ", gen_random_uuid(), 'DANG', gen_random_uuid(), gen_random_uuid(),"
              + " gen_random_uuid(), gen_random_uuid(), "
              + status
              + ")");
    }
    return id;
  }

  private UUID snapshot(UUID t, UUID profile) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(insertSnapshot(t, id, profile));
    }
    return id;
  }

  private static String insertSnapshot(UUID t, UUID id, UUID profile) {
    return "INSERT INTO appraisal.score_snapshot (id, created_by, updated_by, period_id,"
        + " period_bucket, profile_id, flow, snapshot_kind, score_result_id, policy_pack_version_id,"
        + " policy_content_hash, policy_version, input_hash, total_score, auto_rating_code,"
        + " rule_checks, group_scores_json, computed_at, snapshot_hash) VALUES ('"
        + id
        + "', '"
        + t
        + "', '"
        + t
        + "', gen_random_uuid(), "
        + YEAR_BUCKET
        + ", '"
        + profile
        + "', 'DANG', 'TAM_TINH', gen_random_uuid(), gen_random_uuid(), "
        + HASH
        + ", 'KHOI_DANG v2.0', "
        + HASH
        + ", 72.5, 'HTNV', '[]', '{}', now(), "
        + HASH
        + ")";
  }

  private UUID proposal(UUID t, UUID profile, UUID snap, String level, String role)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(insertProposal(t, id, profile, snap, level, role));
    }
    return id;
  }

  private static String insertProposal(
      UUID t, UUID id, UUID profile, UUID snap, String level, String role) {
    return "INSERT INTO appraisal.rating_proposal (id, created_by, updated_by, period_id,"
        + " period_bucket, profile_id, level_kind, rating_code, score_snapshot_id, actor_id,"
        + " actor_role, is_current, proposed_at) VALUES ('"
        + id
        + "', '"
        + t
        + "', '"
        + t
        + "', gen_random_uuid(), "
        + YEAR_BUCKET
        + ", '"
        + profile
        + "', "
        + level
        + ", 'HTNV', '"
        + snap
        + "', '"
        + t
        + "', "
        + role
        + ", false, now())";
  }

  private static Map<String, Object> snapshotColumns(UUID tenant, UUID resultId) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("profile_id", id(tenant, "profile"));
    r.put("flow", "NHA_NUOC");
    r.put("snapshot_kind", "CHOT_KY");
    r.put("score_result_id", resultId);
    r.put("policy_pack_version_id", id(tenant, "pack"));
    r.put("policy_content_hash", new byte[32]);
    r.put("policy_version", "KHOI_NN v1.0");
    r.put("input_hash", new byte[32]);
    r.put("total_score", new BigDecimal("80.000"));
    r.put("general_criteria_score", new BigDecimal("25.000"));
    r.put("kpi_score", new BigDecimal("55.000"));
    r.put("auto_rating_code", "HTTNV");
    r.put("rule_checks", jsonb("[]"));
    r.put("group_scores_json", jsonb("{}"));
    r.put("computed_at", OffsetDateTime.parse("2027-01-05T08:00:00Z"));
    r.put("snapshot_hash", new byte[32]);
    return r;
  }

  private static Map<String, Object> row(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    r.put("period_bucket", YEAR_BUCKET);
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
