package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.StepCompleted;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Máy trạng thái hồ sơ đánh giá với CSDL thật (CORE-04-03): mức chèn dòng mới và thôi hiện hành
 * dòng cũ, giải trình mở khoá bước, {@code If-Match} lệch trả xung đột, EVT-11 phát ở mọi bước.
 */
class AppraisalProfileServiceIT {

  private static final int BUCKET = 20271;
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID person = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final List<StepCompleted> published = new ArrayList<>();
  private SingleConnectionDataSource ds;
  private AppraisalProfileService service;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String t :
          List.of("appraisal_profile", "score_snapshot", "rating_proposal", "rating_explanation")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal." + t + "', " + BUCKET + ")");
      }
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    Connection c =
        DriverManager.getConnection(
            pg.getJdbcUrl(), "svc_appraisal", MissionCoreTestDatabase.TEST_DB_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    ds = new SingleConnectionDataSource(c, true);
    service =
        new AppraisalProfileService(
            new JdbcAppraisalProfileRepository(JdbcClient.create(ds)),
            (t, id, step, version) -> published.add(step),
            null,
            Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void chuoiTuDanhGiaDenThamDinhCoGiaiTrinh() throws SQLException {
    TenantId t = TenantId.of(tenant);
    UUID profile = profileWithSnapshot();
    service.transition(
        t,
        profile,
        null,
        UUID.randomUUID(),
        (p, row) ->
            p.openSelfAssessment(row.currentSnapshotId(), row.autoRating(), UUID.randomUUID()));

    CurrentRating first =
        service.recordRating(
            t,
            profile,
            null,
            LevelKind.TU_DE_XUAT,
            RatingLevel.HTXSNV,
            person,
            "staff",
            null,
            null);
    assertThat(first.explanationRequired()).isTrue();
    assertThatThrownBy(
            () -> service.transition(t, profile, null, person, (p, row) -> p.markSubmitted(person)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-422-REASON-REQUIRED"));

    // Ghi lại mức có lý do, rồi giải trình: dòng cũ thôi hiện hành, không bị sửa.
    SealedText reason = new SealedText(new byte[] {1, 2, 3}, UUID.randomUUID());
    service.recordRating(
        t, profile, null, LevelKind.TU_DE_XUAT, RatingLevel.HTXSNV, person, "staff", reason, null);
    service.recordExplanation(
        t, profile, LevelKind.TU_DE_XUAT, "KHAC_MUC_TRUOC", null, null, null, person);
    long version = service.find(t, profile).orElseThrow().rowVersion();
    assertThatThrownBy(
            () ->
                service.transition(
                    t, profile, version + 5, person, (p, row) -> p.markSubmitted(person)))
        .isInstanceOf(VersionConflictException.class);
    AppraisalProfileService.Transitioned submitted =
        service.transition(t, profile, version, person, (p, row) -> p.markSubmitted(person));
    assertThat(submitted.step().to()).isEqualTo(AppraisalStatus.CHO_THAM_DINH);
    assertThat(submitted.rowVersion()).isEqualTo(version + 1);

    service.returnForRevision(t, profile, null, appraiser, "Bổ sung minh chứng");
    assertThat(service.find(t, profile).orElseThrow().returnCount()).isEqualTo(1);

    assertThat(published)
        .extracting(StepCompleted::to)
        .containsExactly(
            AppraisalStatus.TU_DANH_GIA,
            AppraisalStatus.CHO_THAM_DINH,
            AppraisalStatus.TU_DANH_GIA);
    try (Statement st = ds.getConnection().createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FILTER (WHERE is_current), count(*), "
                    + " (SELECT status FROM appraisal.appraisal_profile WHERE id = '"
                    + profile
                    + "'), (SELECT submitted_at IS NOT NULL FROM appraisal.appraisal_profile WHERE id = '"
                    + profile
                    + "') FROM appraisal.rating_proposal WHERE profile_id = '"
                    + profile
                    + "'")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
      assertThat(rs.getInt(2)).isEqualTo(2);
      assertThat(rs.getString(3)).isEqualTo("TU_DANH_GIA");
      assertThat(rs.getBoolean(4)).isTrue();
    }
  }

  @Test
  void hoSoChuaTinhDiemKhongGhiMuc() throws SQLException {
    UUID profile = UUID.randomUUID();
    try (Statement st = ds.getConnection().createStatement()) {
      st.execute(profileSql(profile, "NULL"));
    }
    assertThatThrownBy(
            () ->
                service.recordRating(
                    TenantId.of(tenant),
                    profile,
                    null,
                    LevelKind.TU_DE_XUAT,
                    RatingLevel.HTNV,
                    person,
                    "staff",
                    null,
                    null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).isEqualTo("APPRAISAL-409-NOT-COMPUTED"));
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Hồ sơ CHUA_LAP có ảnh chụp điểm tạm tính mức HTTNV làm ảnh chụp hiện hành. */
  private UUID profileWithSnapshot() throws SQLException {
    UUID profile = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    String sha = "'\\x" + "ab".repeat(32) + "'";
    try (Statement st = ds.getConnection().createStatement()) {
      st.execute(profileSql(profile, "NULL"));
      st.execute(
          "INSERT INTO appraisal.score_snapshot (id, created_by, updated_by, period_id,"
              + " period_bucket, profile_id, flow, snapshot_kind, score_result_id,"
              + " policy_pack_version_id, policy_content_hash, policy_version, input_hash,"
              + " total_score, auto_rating_code, rule_checks, group_scores_json, computed_at,"
              + " snapshot_hash) VALUES ('"
              + snapshot
              + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
              + BUCKET
              + ", '"
              + profile
              + "', 'DANG', 'TAM_TINH', gen_random_uuid(), gen_random_uuid(), "
              + sha
              + ", 'KHOI_DANG v2.0', "
              + sha
              + ", 85, 'HTTNV', '[]', '{}', now(), "
              + sha
              + ")");
      st.execute(
          "UPDATE appraisal.appraisal_profile SET current_snapshot_id = '"
              + snapshot
              + "' WHERE id = '"
              + profile
              + "'");
    }
    return profile;
  }

  private String profileSql(UUID profile, String snapshot) {
    return "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
        + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
        + " policy_pack_version_id, current_snapshot_id) VALUES ('"
        + profile
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + BUCKET
        + ", '"
        + person
        + "', 'DANG', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + snapshot
        + ")";
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
