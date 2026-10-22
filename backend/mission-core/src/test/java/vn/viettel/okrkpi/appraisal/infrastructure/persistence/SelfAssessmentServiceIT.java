package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentSubmission;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Phiếu tự đánh giá với CSDL thật (CORE-04-04): lưu nháp lần sau cùng phiên bản trả 409, cột {@code
 * _enc} không chứa văn bản rõ, nộp hai lần chỉ mở một yêu cầu ký, hồ sơ chỉ sang {@code
 * CHO_THAM_DINH} sau EVT-18 (ghi mức {@code TU_DE_XUAT}, giải trình, EVT-11 và EVT-21 qua outbox).
 */
class SelfAssessmentServiceIT {

  private static final int BUCKET = 20272;
  private static final String CONTENT = "Hoàn thành đúng hạn các nhiệm vụ then chốt của quý.";
  private static final String REASON = "Đơn vị ghi nhận giải pháp số hoá báo cáo tuần của tôi.";
  private static final byte[] KEY = new byte[32];
  private static PostgreSQLContainer<?> pg;
  private static FieldEncryptor encryptor;

  private final UUID tenant = UUID.randomUUID();
  private final UUID person = UUID.randomUUID();
  private final List<UUID> signRequests = new ArrayList<>();
  private SingleConnectionDataSource ds;
  private AppraisalProfileService profiles;
  private SelfAssessmentService service;
  private SelfAssessmentSubmission submission;
  private TransactionTemplate tx;

  @BeforeAll
  static void start() throws SQLException {
    pg = MissionCoreTestDatabase.container();
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String t :
          List.of(
              "appraisal_profile",
              "score_snapshot",
              "rating_proposal",
              "rating_explanation",
              "self_assessment",
              "general_criteria_result")) {
        st.execute(
            "SELECT appraisal.fn_create_period_partitions('appraisal." + t + "', " + BUCKET + ")");
      }
    }
    DekService deks = mock(DekService.class);
    UUID dekId = UUID.randomUUID();
    when(deks.getOrCreateActiveDek(any(), any()))
        .thenAnswer(inv -> dek(dekId, inv.getArgument(0), inv.getArgument(1)));
    when(deks.getDekForDecryption(any(), any()))
        .thenAnswer(inv -> dek(dekId, inv.getArgument(0), DekPurpose.NHAN_XET_DANH_GIA));
    encryptor = new FieldEncryptor(deks, new JcaAesGcmCryptoProvider());
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
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    JdbcAppraisalProfileRepository profileRepo = new JdbcAppraisalProfileRepository(jdbc);
    profiles =
        new AppraisalProfileService(
            profileRepo, new OutboxAppraisalStepEvents(outbox), null, Clock.systemUTC());
    service =
        new SelfAssessmentService(
            profileRepo,
            new JdbcSelfAssessmentRepository(jdbc),
            profiles,
            new FieldEncryptorTextSealer(encryptor),
            null,
            null,
            new OutboxSelfAssessmentNotices(outbox),
            new ObjectMapper(),
            Clock.systemUTC());
    submission =
        new SelfAssessmentSubmission(
            service,
            (t, profileId, provider, key, auth) -> {
              // SVC-10 đọc tài liệu qua cổng nội bộ trước khi trả mã yêu cầu ký.
              assertThat(service.signSource(t, profileId).orElseThrow().ready()).isTrue();
              UUID id = UUID.randomUUID();
              signRequests.add(id);
              return id;
            });
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void luuNhapLanSauCungPhienBanTraXungDotVaCotEncKhongChuaBanRo() throws SQLException {
    TenantId t = TenantId.of(tenant);
    UUID profile = openProfile();
    long v = version(profile);
    SelfAssessmentService.View saved = service.save(t, profile, v, person, form());
    assertThat(saved.profile().rowVersion()).isEqualTo(v + 1);
    assertThat(saved.form().content()).isEqualTo(CONTENT);
    assertThat(saved.criteria())
        .extracting(r -> r.code() + ":" + r.level())
        .containsExactly("TCC-01:CO_BAN_DAM_BAO", "TCC-02:CO_BAN_DAM_BAO", "TCC-03:CO_BAN_DAM_BAO");
    // Lần lưu thứ hai mang cùng If-Match: 409.
    assertThatThrownBy(() -> service.save(t, profile, v, person, form()))
        .isInstanceOf(VersionConflictException.class);

    try (Statement st = ds.getConnection().createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT content_enc, reason_enc, strengths_enc, dek_id, structured_data::text"
                    + " FROM appraisal.self_assessment WHERE profile_id = '"
                    + profile
                    + "'")) {
      assertThat(rs.next()).isTrue();
      for (int i = 1; i <= 3; i++) {
        String raw = new String(rs.getBytes(i), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("Hoàn thành").doesNotContain("giải pháp");
      }
      assertThat(rs.getObject(4)).isNotNull();
      assertThat(rs.getString(5)).contains("TCC-01").doesNotContain(CONTENT);
      assertThat(rs.next()).isFalse();
    }
    assertThat(service.mine(t, person, null, "DANG", null, 20))
        .singleElement()
        .satisfies(p -> assertThat(p.id()).isEqualTo(profile));
    assertThat(service.mine(t, person, null, "NHA_NUOC", null, 20)).isEmpty();
  }

  @Test
  void nopHaiLanChiMotYeuCauKyVaChiChuyenBuocSauEvt18() throws SQLException {
    TenantId t = TenantId.of(tenant);
    UUID profile = openProfile();
    service.save(t, profile, version(profile), person, form());
    long v = version(profile);
    UUID first = submission.submit(t, profile, v, person, "MYSIGN", null, "k-1", null);
    UUID again = submission.submit(t, profile, v, person, "MYSIGN", null, "k-1", null);
    assertThat(again).isEqualTo(first);
    assertThat(signRequests).containsExactly(first);
    assertThat(status(profile)).isEqualTo("TU_DANH_GIA");
    assertThat(service.signJob(t, first).orElseThrow().signed()).isFalse();

    Instant signedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    assertThat(tx.<Boolean>execute(x -> service.onSigned(t, first, signedAt))).isTrue();
    assertThat(tx.<Boolean>execute(x -> service.onSigned(t, first, signedAt))).isFalse();
    assertThat(status(profile)).isEqualTo("CHO_THAM_DINH");
    assertThat(service.signJob(t, first).orElseThrow().signed()).isTrue();

    SelfAssessmentService.View view = service.view(t, profile, person).orElseThrow();
    assertThat(view.stored().signedAt()).isEqualTo(signedAt);
    assertThat(view.trail())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.levelKind().name()).isEqualTo("TU_DE_XUAT");
              assertThat(r.rating()).isEqualTo(RatingLevel.HTNV);
              assertThat(r.current()).isTrue();
            });
    try (Statement st = ds.getConnection().createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT (SELECT count(*) FROM appraisal.rating_explanation WHERE profile_id = '"
                    + profile
                    + "'), (SELECT reason_enc IS NOT NULL FROM appraisal.rating_proposal"
                    + " WHERE profile_id = '"
                    + profile
                    + "'), (SELECT string_agg(topic, ',' ORDER BY id) FROM appraisal.outbox_event"
                    + " WHERE aggregate_id = '"
                    + profile
                    + "')")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
      assertThat(rs.getBoolean(2)).isTrue();
      assertThat(rs.getString(3))
          .isEqualTo(
              "appraisal.profile.stepcompleted.v1,appraisal.profile.stepcompleted.v1,"
                  + "notify.message.requested.v1");
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private static SelfAssessmentForm form() {
    return new SelfAssessmentForm(
        List.of(
            new SelfAssessmentForm.Criterion("TCC-01", true),
            new SelfAssessmentForm.Criterion("TCC-02", true),
            new SelfAssessmentForm.Criterion("TCC-03", false)),
        RatingLevel.HTNV,
        null,
        null,
        REASON,
        CONTENT,
        "Chủ động, đúng hạn",
        null,
        null,
        List.of(Map.of("taskId", UUID.randomUUID().toString())));
  }

  /** Hồ sơ có ảnh chụp điểm mức HTTNV, đã mở bước tự đánh giá. */
  private UUID openProfile() throws SQLException {
    UUID profile = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    String sha = "'\\x" + "ab".repeat(32) + "'";
    try (Statement st = ds.getConnection().createStatement()) {
      st.execute(
          "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
              + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
              + " policy_pack_version_id) VALUES ('"
              + profile
              + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
              + BUCKET
              + ", '"
              + person
              + "', 'DANG', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
              + " gen_random_uuid())");
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
    tx.execute(
        x ->
            profiles.transition(
                TenantId.of(tenant),
                profile,
                null,
                UUID.randomUUID(),
                (p, row) ->
                    p.openSelfAssessment(
                        row.currentSnapshotId(), row.autoRating(), UUID.randomUUID())));
    return profile;
  }

  private long version(UUID profile) {
    return profiles.find(TenantId.of(tenant), profile).orElseThrow().rowVersion();
  }

  private String status(UUID profile) throws SQLException {
    try (Statement st = ds.getConnection().createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT status FROM appraisal.appraisal_profile WHERE id = '" + profile + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static CachedDek dek(UUID dekId, UUID tenant, DekPurpose purpose) {
    return new CachedDek(
        dekId, tenant, purpose, LocalDate.of(2026, 10, 1), DekStatus.ACTIVE, KEY.clone());
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
