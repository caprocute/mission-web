package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService.ScoreView;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.Outcome;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.ScoreComputed;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Lập hồ sơ quý và ảnh chụp điểm với CSDL thật (CORE-04-02): người điều động sau ngày khoá kỳ vẫn
 * được lập hồ sơ ở đơn vị cũ; đảng viên có thêm hồ sơ luồng Đảng; EVT-09 xử lý hai lần chỉ tạo một
 * bộ hồ sơ; EVT-10 chính thức mở phiếu tự đánh giá và phát EVT-11, EVT-21; EVT-10 cũ hơn ảnh chụp
 * hiện hành bị bỏ qua; ảnh chụp mới thay ảnh chụp cũ hai bước. Đọc điểm theo luồng (CORE-04-09).
 */
class ProfileSetupIT {

  private static final int BUCKET = 20283;
  private static final Instant LOCKED = Instant.parse("2028-09-30T18:30:00Z");
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID period = UUID.randomUUID();
  private final UUID oldUnit = UUID.randomUUID();
  private final UUID newUnit = UUID.randomUUID();
  private final UUID moved = UUID.randomUUID();
  private final UUID retired = UUID.randomUUID();
  private final UUID pack = UUID.randomUUID();
  private SingleConnectionDataSource ds;
  private ProfileSetupService service;
  private TransactionTemplate tx;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String t : List.of("appraisal_profile", "score_snapshot", "rating_proposal")) {
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
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    JdbcClient jdbc = JdbcClient.create(ds);
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(ds),
            "appraisal",
            (topic, payload) ->
                new SerializedPayload(payload.toString().getBytes(StandardCharsets.UTF_8), 1L, 1),
            new ObjectMapper(),
            Clock.systemUtc());
    AppraisalProfileService profiles =
        new AppraisalProfileService(
            new JdbcAppraisalProfileRepository(jdbc),
            new OutboxAppraisalStepEvents(outbox),
            null,
            java.time.Clock.systemUTC());
    PolicyQueryApi policies = mock(PolicyQueryApi.class);
    when(policies.resolve(any(), any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(),
                    "GOI",
                    pack,
                    "2028.1",
                    "ab",
                    null,
                    UUID.randomUUID(),
                    true)));
    service =
        new ProfileSetupService(
            profiles,
            new JdbcProfileSetupRepository(jdbc),
            new OutboxSetupNotices(outbox),
            policies,
            null,
            null,
            Clock.systemUtc());
    seedOrg();
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void lapHoSoTheoToChucTaiNgayKhoaKyVaChayLaiAnToan() {
    TenantId t = TenantId.of(tenant);
    assertThat((Integer) tx.execute(x -> service.onPeriodLocked(t, period, BUCKET, LOCKED)))
        .isEqualTo(2);
    assertThat((Integer) tx.execute(x -> service.onPeriodLocked(t, period, BUCKET, LOCKED)))
        .isZero();
    List<String> rows =
        JdbcClient.create(ds)
            .sql(
                "SELECT flow || ':' || org_unit_id || ':' || status || ':' || org_asof_valid_date"
                    + " || ':' || policy_pack_version_id FROM appraisal.appraisal_profile"
                    + " WHERE period_id = ? AND person_id = ? ORDER BY flow")
            .params(period, moved)
            .query(String.class)
            .list();
    assertThat(rows)
        .containsExactly(
            "DANG:" + oldUnit + ":CHUA_LAP:2028-10-01:" + pack,
            "NHA_NUOC:" + oldUnit + ":CHUA_LAP:2028-10-01:" + pack);
    assertThat(count("appraisal.appraisal_profile WHERE person_id = '" + retired + "'")).isZero();
  }

  @Test
  void anhChupChinhThucMoPhieuVaSuKienCuBiBoQua() {
    TenantId t = TenantId.of(tenant);
    tx.execute(x -> service.onPeriodLocked(t, period, BUCKET, LOCKED));
    assertThat(
            (Outcome)
                tx.execute(
                    x ->
                        service.onScoreComputed(
                            t, score(profileOf("NHA_NUOC"), "CHOT_KY", LOCKED.plusSeconds(1)))))
        .isEqualTo(Outcome.INCOMPLETE);
    UUID profile = profileOf("DANG");
    Instant at = LOCKED.plusSeconds(600);
    assertThat(
            (Outcome) tx.execute(x -> service.onScoreComputed(t, score(profile, "TAM_TINH", at))))
        .isEqualTo(Outcome.STORED);
    assertThat(status(profile)).isEqualTo("CHUA_LAP");
    assertThat(
            (Outcome)
                tx.execute(
                    x -> service.onScoreComputed(t, score(profile, "CHOT_KY", at.plusSeconds(60)))))
        .isEqualTo(Outcome.OPENED);
    assertThat(status(profile)).isEqualTo("TU_DANH_GIA");
    assertThat(
            (Outcome)
                tx.execute(
                    x -> service.onScoreComputed(t, score(profile, "CHOT_KY", at.minusSeconds(1)))))
        .isEqualTo(Outcome.STALE);
    assertThat(count("appraisal.score_snapshot WHERE profile_id = '" + profile + "'")).isEqualTo(2);
    assertThat(
            count(
                "appraisal.score_snapshot WHERE superseded_by_id IS NULL AND profile_id = '"
                    + profile
                    + "'"))
        .isEqualTo(1);
    assertThat(
            JdbcClient.create(ds)
                .sql(
                    "SELECT p.current_snapshot_id = s.id FROM appraisal.appraisal_profile p JOIN"
                        + " appraisal.score_snapshot s ON s.profile_id = p.id AND"
                        + " s.superseded_by_id IS NULL WHERE p.id = ?")
                .param(profile)
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(count("appraisal.outbox_event WHERE aggregate_id = '" + profile + "'")).isEqualTo(2);
  }

  /** CORE-04-09: đọc điểm hiện hành theo luồng, luồng kia chưa tính thì 409. */
  @Test
  void docDiemTheoLuong() {
    TenantId t = TenantId.of(tenant);
    tx.execute(x -> service.onPeriodLocked(t, period, BUCKET, LOCKED));
    UUID dang = profileOf("DANG");
    Instant at = LOCKED.plusSeconds(600);
    tx.execute(x -> service.onScoreComputed(t, score(dang, "TAM_TINH", at)));
    tx.execute(x -> service.onScoreComputed(t, score(dang, "CHOT_KY", at.plusSeconds(60))));
    JdbcClient jdbc = JdbcClient.create(ds);
    ScoreViewService scores =
        new ScoreViewService(
            new JdbcSelfAssessmentRepository(jdbc), new JdbcScoreSnapshotReader(jdbc));

    ScoreView view = scores.view(t, dang, null, moved, p -> false).orElseThrow();
    assertThat(view.snapshot().kind()).isEqualTo("CHOT_KY");
    assertThat(view.snapshot().totalScore()).isEqualByComparingTo("81.25");
    assertThat(view.snapshot().autoRatingCode()).isEqualTo("HTTNV");
    assertThat(view.snapshot().snapshotHash()).hasSize(32);
    assertThat(view.snapshot().ruleChecks()).isEqualTo("[]");
    assertThat(view.snapshot().supersededById()).isNull();
    assertThatThrownBy(() -> scores.view(t, dang, "NHA_NUOC", moved, p -> false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("APPRAISAL-409-NOT-COMPUTED"));
    assertThatThrownBy(() -> scores.view(t, dang, null, UUID.randomUUID(), p -> false))
        .isInstanceOf(BusinessException.class);
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Người được điều động sang đơn vị mới sau ngày khoá kỳ; một người đã nghỉ hưu. */
  private void seedOrg() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (UUID unit : List.of(oldUnit, newUnit)) {
        st.execute(
            "INSERT INTO org_replica.org_unit_v (tenant_id, id, valid_from, code, name, unit_type,"
                + " source_version, source_event_id) VALUES ('"
                + tenant
                + "', '"
                + unit
                + "', '2020-01-01', 'DV', 'Đơn vị', 'CO_QUAN', 1, gen_random_uuid())");
      }
      person(st, moved, true, "DANG_CONG_TAC");
      person(st, retired, false, "NGHI_HUU");
      assignment(st, moved, oldUnit, "2028-01-01", "2028-10-15");
      assignment(st, moved, newUnit, "2028-10-16", "infinity");
      assignment(st, retired, oldUnit, "2028-01-01", "infinity");
    }
  }

  private void person(Statement st, UUID id, boolean party, String status) throws SQLException {
    st.execute(
        "INSERT INTO org_replica.person_v (tenant_id, id, full_name, is_party_member,"
            + " employment_status, source_version, source_event_id) VALUES ('"
            + tenant
            + "', '"
            + id
            + "', 'Cán bộ', "
            + party
            + ", '"
            + status
            + "', 1, gen_random_uuid())");
  }

  private void assignment(Statement st, UUID person, UUID unit, String from, String to)
      throws SQLException {
    st.execute(
        "INSERT INTO org_replica.assignment_v (tenant_id, id, valid_from, valid_to, person_id,"
            + " org_unit_id, job_title_id, kind, branch, source_version, source_event_id) VALUES ('"
            + tenant
            + "', gen_random_uuid(), '"
            + from
            + "', '"
            + to
            + "', '"
            + person
            + "', '"
            + unit
            + "', gen_random_uuid(), 'CHINH', 'CHINH', 1, gen_random_uuid())");
  }

  private ScoreComputed score(UUID profile, String kind, Instant at) {
    return new ScoreComputed(
        UUID.randomUUID(),
        profile,
        kind,
        new BigDecimal("81.250"),
        "HTTNV",
        new byte[32],
        new byte[32],
        at);
  }

  private UUID profileOf(String flow) {
    return JdbcClient.create(ds)
        .sql("SELECT id FROM appraisal.appraisal_profile WHERE person_id = ? AND flow = ?")
        .params(moved, flow)
        .query(UUID.class)
        .single();
  }

  private String status(UUID profile) {
    return JdbcClient.create(ds)
        .sql("SELECT status FROM appraisal.appraisal_profile WHERE id = ?")
        .param(profile)
        .query(String.class)
        .single();
  }

  private long count(String from) {
    return JdbcClient.create(ds).sql("SELECT count(*) FROM " + from).query(Long.class).single();
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
