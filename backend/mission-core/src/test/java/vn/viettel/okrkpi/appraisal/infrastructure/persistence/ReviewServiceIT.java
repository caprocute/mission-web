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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.AppraisalDraft;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.Prepared;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Thẩm định, trả lại, đề xuất với CSDL thật (CORE-04-05): người đã nghiệm thu minh chứng của hồ sơ
 * bị từ chối (BR-09-14, MSG-09-08); trả lại lần ba bị chặn; kết luận thẩm định chỉ chuyển {@code
 * DA_THAM_DINH} sau EVT-18; đề xuất khác mức thẩm định thiếu lý do trả 422 (MSG-09-12); hai người
 * cùng lưu với một {@code If-Match} thì người sau nhận 409; hàng đợi lọc theo phạm vi.
 */
class ReviewServiceIT {

  private static final int BUCKET = 20273;
  private static final String COMMENT = "Hoàn thành tốt các nhiệm vụ trọng tâm của quý.";
  private static final String REASON = "Mức đề xuất phản ánh đóng góp vượt trội trong quý này.";
  private static final byte[] KEY = new byte[32];
  private static PostgreSQLContainer<?> pg;
  private static FieldEncryptor encryptor;

  private final UUID tenant = UUID.randomUUID();
  private final UUID person = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID acceptor = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID manager = UUID.randomUUID();
  private final AtomicBoolean skipProposal = new AtomicBoolean();
  private SingleConnectionDataSource ds;
  private AppraisalProfileService profiles;
  private ReviewService review;
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
        TestPostgres.connectAsTenant(
            pg.getJdbcUrl(), "svc_appraisal", MissionCoreTestDatabase.TEST_DB_PASSWORD, tenant);
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
    review =
        new ReviewService(
            profileRepo,
            new JdbcSelfAssessmentRepository(jdbc),
            new JdbcReviewRepository(jdbc),
            profiles,
            new FieldEncryptorTextSealer(encryptor),
            (t, period, who) -> who.equals(person) ? Set.of(acceptor) : Set.of(),
            new OutboxReviewNotices(outbox),
            (t, row) -> skipProposal.get(),
            Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void nguoiDaNghiemThuMinhChungKhongThamDinhVaKhongTraLai() throws SQLException {
    TenantId t = TenantId.of(tenant);
    UUID profile = submittedProfile();
    long v = version(profile);
    assertThat(review.participants(t, profile).orElseThrow().toAuthzResource().participants())
        .containsEntry("acceptor", List.of(acceptor.toString()))
        .containsEntry("appraisee", List.of(person.toString()));
    assertThatThrownBy(
            () ->
                review.prepareAppraisal(
                    t,
                    profile,
                    v,
                    acceptor,
                    new AppraisalDraft(RatingLevel.HTTNV, COMMENT, null, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES")
        .satisfies(e -> assertThat(((BusinessException) e).msg()).isEqualTo("MSG-09-08"));
    assertThatThrownBy(() -> review.returnProfile(t, profile, v, acceptor, REASON))
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES");
    assertThatThrownBy(() -> review.returnProfile(t, profile, v, person, REASON))
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES");
    assertThat(status(profile)).isEqualTo("CHO_THAM_DINH");
  }

  @Test
  void traLaiLanBaBiChanVaBaoNguoiDuocDanhGia() throws SQLException {
    TenantId t = TenantId.of(tenant);
    UUID profile = submittedProfile();
    for (int i = 0; i < 2; i++) {
      long current = version(profile);
      tx.execute(x -> review.returnProfile(t, profile, current, appraiser, REASON));
      assertThat(status(profile)).isEqualTo("TU_DANH_GIA");
      resubmit(profile);
    }
    long v = version(profile);
    assertThatThrownBy(() -> review.returnProfile(t, profile, v, appraiser, REASON))
        .isInstanceOf(BusinessException.class)
        .hasMessage("APPRAISAL-409-RETURN-LIMIT");
    assertThatThrownBy(() -> review.returnProfile(t, profile, v, appraiser, " "))
        .hasMessage("APPRAISAL-422-REASON-REQUIRED");
    assertThat(
            count(
                "appraisal.outbox_event WHERE topic = 'notify.message.requested.v1'"
                    + " AND aggregate_id = '"
                    + profile
                    + "'"))
        .isEqualTo(2);
    assertThat(
            count("appraisal.appraisal_profile WHERE return_count = 2 AND id = '" + profile + "'"))
        .isEqualTo(1);
  }

  @Test
  void thamDinhChoKyRoiDeXuatKhacMucPhaiCoLyDo() throws SQLException {
    TenantId t = TenantId.of(tenant);
    UUID profile = submittedProfile();
    long v = version(profile);
    // Khác mức tự đề xuất (HTTNV của ảnh chụp) mà không lý do: 422 MSG-09-06.
    assertThatThrownBy(
            () ->
                review.prepareAppraisal(
                    t,
                    profile,
                    v,
                    appraiser,
                    new AppraisalDraft(RatingLevel.HTXSNV, COMMENT, null, null)))
        .hasMessage("APPRAISAL-422-REASON-REQUIRED")
        .satisfies(e -> assertThat(((BusinessException) e).msg()).isEqualTo("MSG-09-06"));
    assertThat(count("appraisal.rating_proposal WHERE profile_id = '" + profile + "'")).isZero();

    Prepared prepared =
        review.prepareAppraisal(
            t, profile, v, appraiser, new AppraisalDraft(RatingLevel.HTTNV, COMMENT, null, null));
    // Người thẩm định thứ hai lưu cùng If-Match: 409 (MSG-09-07).
    UUID other = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                review.prepareAppraisal(
                    t,
                    profile,
                    v,
                    other,
                    new AppraisalDraft(RatingLevel.HTTNV, COMMENT, null, null)))
        .isInstanceOf(VersionConflictException.class);
    UUID signRequest = UUID.randomUUID();
    review.attachSignRequest(t, prepared, signRequest);
    // Gửi lại khi đang chờ ký trả yêu cầu ký cũ.
    assertThat(
            review
                .prepareAppraisal(
                    t,
                    profile,
                    0,
                    appraiser,
                    new AppraisalDraft(RatingLevel.HTTNV, COMMENT, null, null))
                .signRequestId())
        .isEqualTo(signRequest);
    ReviewService.SignSource source = review.signSource(t, profile).orElseThrow();
    assertThat(source.ready()).isTrue();
    assertThat(source.signer()).isEqualTo(appraiser);
    assertThat(source.contentHash()).hasSize(32);
    assertThat(status(profile)).isEqualTo("CHO_THAM_DINH");
    assertThat(review.signJob(t, signRequest).orElseThrow().signed()).isFalse();

    Instant signedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    assertThat(tx.<Boolean>execute(x -> review.onAppraisalSigned(t, signRequest, signedAt)))
        .isTrue();
    assertThat(tx.<Boolean>execute(x -> review.onAppraisalSigned(t, signRequest, signedAt)))
        .isFalse();
    assertThat(status(profile)).isEqualTo("DA_THAM_DINH");
    assertThat(review.signJob(t, signRequest).orElseThrow().signed()).isTrue();
    assertThat(
            count(
                "appraisal.general_criteria_result WHERE stage = 'THAM_DINH' AND profile_id = '"
                    + profile
                    + "'"))
        .isEqualTo(2);
    try (Statement st = ds.getConnection().createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT comment_enc FROM appraisal.rating_proposal WHERE level_kind = 'THAM_DINH'"
                    + " AND profile_id = '"
                    + profile
                    + "'")) {
      rs.next();
      assertThat(new String(rs.getBytes(1), StandardCharsets.UTF_8)).doesNotContain("nhiệm vụ");
    }

    long appraised = version(profile);
    assertThatThrownBy(
            () -> review.propose(t, profile, appraised, appraiser, RatingLevel.HTTNV, null, null))
        .hasMessage("APPRAISAL-403-SEPARATION-OF-DUTIES");
    assertThatThrownBy(
            () -> review.propose(t, profile, appraised, manager, RatingLevel.HTNV, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessage("APPRAISAL-422-REASON-REQUIRED")
        .satisfies(e -> assertThat(((BusinessException) e).msg()).isEqualTo("MSG-09-12"));
    tx.execute(
        x -> review.propose(t, profile, appraised, manager, RatingLevel.HTNV, REASON, "Đồng ý"));
    assertThat(status(profile)).isEqualTo("CHO_QUYET_DINH");
    assertThat(count("appraisal.rating_explanation WHERE profile_id = '" + profile + "'"))
        .isEqualTo(1);

    List<ReviewService.QueueItem> queue =
        review.queue(
            t,
            new QueueFilter(null, "DANG", null, true, Set.of("CHO_QUYET_DINH"), null, null),
            new Scope("p.org_unit_id = ?", List.of(unit)),
            null,
            20);
    assertThat(queue)
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.row().profile().id()).isEqualTo(profile);
              assertThat(i.levels())
                  .containsEntry(LevelKind.THAM_DINH, RatingLevel.HTTNV)
                  .containsEntry(LevelKind.DE_XUAT, RatingLevel.HTNV);
            });
    assertThat(
            review.queue(
                t,
                new QueueFilter(null, null, null, true, Set.of("CHO_QUYET_DINH"), null, null),
                Scope.NONE,
                null,
                20))
        .isEmpty();
  }

  @Test
  void boQuaCapDeXuatThiChuyenThangChoQuyetDinh() throws SQLException {
    TenantId t = TenantId.of(tenant);
    skipProposal.set(true);
    UUID profile = submittedProfile();
    Prepared prepared =
        review.prepareAppraisal(
            t,
            profile,
            version(profile),
            appraiser,
            new AppraisalDraft(
                RatingLevel.HTTNV,
                COMMENT,
                null,
                List.of(
                    new vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm
                        .Criterion("TCC-01", true))));
    UUID signRequest = UUID.randomUUID();
    review.attachSignRequest(t, prepared, signRequest);
    tx.execute(x -> review.onAppraisalSigned(t, signRequest, Instant.now()));
    assertThat(status(profile)).isEqualTo("CHO_QUYET_DINH");
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Hồ sơ có ảnh chụp điểm mức HTTNV, tiêu chí chung tự nhận, đang chờ thẩm định. */
  private UUID submittedProfile() throws SQLException {
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
              + "', 'DANG', '"
              + unit
              + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid())");
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
      for (String code : List.of("TCC-01", "TCC-02")) {
        st.execute(
            "INSERT INTO appraisal.general_criteria_result (id, created_by, updated_by, period_id,"
                + " period_bucket, profile_id, stage, criteria_code, compliance_ratio, level, met)"
                + " VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
                + " gen_random_uuid(), "
                + BUCKET
                + ", '"
                + profile
                + "', 'TU_DANH_GIA', '"
                + code
                + "', 1, 'THUC_HIEN_TOT', true)");
      }
    }
    TenantId t = TenantId.of(tenant);
    tx.execute(
        x ->
            profiles.transition(
                t,
                profile,
                null,
                person,
                (p, row) ->
                    p.openSelfAssessment(row.currentSnapshotId(), row.autoRating(), person)));
    resubmit(profile);
    return profile;
  }

  private void resubmit(UUID profile) {
    tx.execute(
        x ->
            profiles.transition(
                TenantId.of(tenant), profile, null, person, (p, row) -> p.markSubmitted(person)));
  }

  private long version(UUID profile) {
    return profiles.find(TenantId.of(tenant), profile).orElseThrow().rowVersion();
  }

  private int count(String fromWhere) throws SQLException {
    try (Statement st = ds.getConnection().createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + fromWhere)) {
      rs.next();
      return rs.getInt(1);
    }
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
