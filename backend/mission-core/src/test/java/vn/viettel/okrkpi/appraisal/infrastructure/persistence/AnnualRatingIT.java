package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
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
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.SpecialCase;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases.Treatment;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualReassignmentService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualSetupService;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualNotices;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.AnnualRow;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.Filter;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRecomputeRequests;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.Outcome;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService.ScoreComputed;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.api.TaskApi;

/**
 * Xếp loại năm với CSDL thật (CORE-04-10): lập hồ sơ năm khi quý IV khoá theo bốn quý, thiếu quý
 * không đặc thù thì không lập; cờ có quý KHTNV và hai quý liên tiếp KHTNV; khiếu nại quý mở chặn
 * quyết định năm; ảnh chụp năm tham chiếu các quý; mức năm đổi sau hậu kiểm đưa hồ sơ về bước quyết
 * định và giữ quyết định cũ ở dạng bị thay thế.
 */
class AnnualRatingIT {

  private static final int YEAR = 2029;
  private static final int BUCKET = YEAR * 10;
  private static final Instant LOCKED = Instant.parse("2030-01-10T02:00:00Z");
  private static PostgreSQLContainer<?> pg;

  private final UUID tenant = UUID.randomUUID();
  private final UUID annualPeriod = UUID.randomUUID();
  private final UUID q4Period = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID pack = UUID.randomUUID();
  private final UUID weak = UUID.randomUUID();
  private final UUID twice = UUID.randomUUID();
  private final UUID missing = UUID.randomUUID();
  private final UUID special = UUID.randomUUID();
  private final UUID complaint = UUID.randomUUID();
  private final UUID apart = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private SingleConnectionDataSource ds;
  private TransactionTemplate tx;
  private JdbcAnnualRatingRepository annual;
  private AnnualSetupService setup;
  private ProfileSetupService scores;
  private AnnualRecomputeRequests recompute;

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
              "annual_rating",
              "prospect_group")) {
        for (int b = BUCKET; b <= BUCKET + 4; b++) {
          if (b != BUCKET && List.of("annual_rating", "prospect_group").contains(t)) {
            continue;
          }
          st.execute(
              "SELECT appraisal.fn_create_period_partitions('appraisal." + t + "', " + b + ")");
        }
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
    annual = new JdbcAnnualRatingRepository(jdbc);
    PeriodApi periods = mock(PeriodApi.class);
    TenantId t = TenantId.of(tenant);
    when(periods.findById(t, PeriodId.of(q4Period)))
        .thenReturn(Optional.of(period(q4Period, "2029-10-01", "2029-12-31")));
    when(periods.findCovering(t, "NAM", LocalDate.parse("2029-12-31")))
        .thenReturn(Optional.of(period(annualPeriod, "2029-01-01", "2029-12-31")));
    AppraisalSpecialCases cases = mock(AppraisalSpecialCases.class);
    when(cases.forPeriod(any(), any())).thenReturn(List.of());
    when(cases.forPeriod(t, annualPeriod))
        .thenReturn(List.of(new SpecialCase(special, null, Treatment.KHONG_XEP_LOAI)));
    setup = new AnnualSetupService(annual, periods, null, cases);
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
    recompute = mock(AnnualRecomputeRequests.class);
    scores =
        new ProfileSetupService(
            profiles,
            new JdbcProfileSetupRepository(jdbc),
            new OutboxSetupNotices(outbox),
            null,
            null,
            null,
            Clock.systemUtc(),
            annual,
            recompute);
    seedQuarters();
  }

  @AfterEach
  void tearDown() {
    ds.destroy();
  }

  @Test
  void lapHoSoNamTheoBonQuyVaCoLienThong() {
    TenantId t = TenantId.of(tenant);
    assertThat((Integer) tx.execute(x -> setup.onQuarterLocked(t, q4Period, BUCKET + 4, LOCKED)))
        .isEqualTo(5);
    assertThat((Integer) tx.execute(x -> setup.onQuarterLocked(t, q4Period, BUCKET + 4, LOCKED)))
        .isZero();
    assertThat(annual.annualProfileOf(t, YEAR, missing, "DANG")).isEmpty();
    assertThat(annual.annualProfileOf(t, YEAR, special, "DANG")).isPresent();

    AnnualRatingService service =
        new AnnualRatingService(annual, new JdbcScoreSnapshotReader(JdbcClient.create(ds)));
    List<AnnualRow> all = service.page(t, filter(false), 50).rows();
    assertThat(all).hasSize(5);
    AnnualRow w = row(all, weak);
    assertThat(w.quarterRatings()).containsExactly("HTTNV", "KHTNV", "HTNV", null);
    assertThat(w.hasKhtnvQuarter()).isTrue();
    assertThat(w.blockReason()).isEqualTo("CO_QUY_KHTNV");
    assertThat(w.reassignFlag()).isFalse();
    AnnualRow tw = row(all, twice);
    assertThat(tw.consecutiveKhtnv()).isTrue();
    assertThat(tw.reassignFlag()).isTrue();
    assertThat(AnnualRatingService.linkageNote(tw)).contains("bố trí lại");
    AnnualRow ap = row(all, apart);
    assertThat(ap.hasKhtnvQuarter()).isTrue();
    assertThat(ap.consecutiveKhtnv()).isFalse();
    assertThat(ap.reassignFlag()).isFalse();
    assertThat(service.page(t, filter(true), 50).rows())
        .extracting(AnnualRow::personId)
        .containsExactlyInAnyOrder(weak, twice, complaint, apart);
    var counts = service.page(t, filter(false), 50).counts();
    assertThat(counts.profilesTotal()).isEqualTo(5);
    assertThat(counts.blockedExcellent()).isEqualTo(4);
    assertThat(counts.reassignProposed()).isEqualTo(1);
    assertThat(counts.awaitingProspectGrouping()).isEqualTo(5);

    UUID weakAnnual = annual.annualProfileOf(t, YEAR, weak, "DANG").orElseThrow();
    UUID complaintAnnual = annual.annualProfileOf(t, YEAR, complaint, "DANG").orElseThrow();
    JdbcBatchDecisionRepository batches = new JdbcBatchDecisionRepository(JdbcClient.create(ds));
    assertThat(batches.withOpenQuarterComplaint(t, BUCKET, List.of(weakAnnual, complaintAnnual)))
        .containsExactly(complaintAnnual);
  }

  @Test
  void anhChupNamThamChieuQuyVaTinhLaiDuaVeBuocQuyetDinh() {
    TenantId t = TenantId.of(tenant);
    tx.execute(x -> setup.onQuarterLocked(t, q4Period, BUCKET + 4, LOCKED));
    UUID profile = annual.annualProfileOf(t, YEAR, weak, "DANG").orElseThrow();
    assertThat(
            (Outcome)
                tx.execute(
                    x ->
                        scores.onScoreComputed(
                            t, score(profile, "CHOT_KY", "HTTNV", LOCKED.plusSeconds(60)))))
        .isEqualTo(Outcome.OPENED);
    JdbcClient jdbc = JdbcClient.create(ds);
    assertThat(
            jdbc.sql(
                    "SELECT jsonb_array_length(detail_json->'quy') FROM appraisal.score_snapshot"
                        + " WHERE period_bucket = ? AND profile_id = ?")
                .params(BUCKET, profile)
                .query(Integer.class)
                .single())
        .isEqualTo(4);
    AnnualRatingService service =
        new AnnualRatingService(annual, new JdbcScoreSnapshotReader(jdbc));
    AnnualRow row = row(service.page(t, filter(false), 50).rows(), weak);
    assertThat(row.annualScore()).isEqualByComparingTo("85.5");
    assertThat(row.systemLevel()).isEqualTo("HTTNV");
    var e = service.explain(t, row.id(), weak, r -> false).orElseThrow();
    assertThat(e.text()).contains("quý II").contains("QĐ khoản 15.5");
    assertThat(e.quarters()).hasSize(4);
    assertThatThrownBy(() -> service.explain(t, row.id(), UUID.randomUUID(), r -> false))
        .hasMessageContaining("COMMON-403-OUT-OF-SCOPE");

    UUID snapshot =
        jdbc.sql(
                "SELECT id FROM appraisal.score_snapshot WHERE period_bucket = ? AND profile_id = ?"
                    + " AND superseded_by_id IS NULL")
            .params(BUCKET, profile)
            .query(UUID.class)
            .single();
    UUID proposal = UUID.randomUUID();
    jdbc.sql(
            "UPDATE appraisal.appraisal_profile SET status = 'DA_KY_SO', final_rating_code = 'HTTNV',"
                + " decider_id = ? WHERE period_bucket = ? AND id = ?")
        .params(decider, BUCKET, profile)
        .update();
    jdbc.sql(
            "INSERT INTO appraisal.rating_proposal (id, created_by, updated_by, period_id,"
                + " period_bucket, profile_id, level_kind, rating_code, score_snapshot_id, actor_id,"
                + " actor_role, is_current, proposed_at) VALUES (?, ?, ?, ?, ?, ?, 'QUYET_DINH',"
                + " 'HTTNV', ?, ?, 'decider', true, now())")
        .params(proposal, decider, decider, annualPeriod, BUCKET, profile, snapshot, decider)
        .update();

    assertThat(
            (Outcome)
                tx.execute(
                    x ->
                        scores.onScoreComputed(
                            t,
                            score(profile, "TINH_LAI_HAU_KIEM", "HTNV", LOCKED.plusSeconds(3600)))))
        .isEqualTo(Outcome.REOPENED);
    assertThat(
            jdbc.sql(
                    "SELECT status || ':' || coalesce(final_rating_code, '-') FROM"
                        + " appraisal.appraisal_profile WHERE period_bucket = ? AND id = ?")
                .params(BUCKET, profile)
                .query(String.class)
                .single())
        .isEqualTo("CHO_QUYET_DINH:-");
    assertThat(
            jdbc.sql(
                    "SELECT is_current FROM appraisal.rating_proposal WHERE period_bucket = ?"
                        + " AND id = ?")
                .params(BUCKET, proposal)
                .query(Boolean.class)
                .single())
        .isFalse();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM appraisal.outbox_event WHERE aggregate_id = ?"
                        + " AND topic = 'notify.message.requested.v1'")
                .param(profile)
                .query(Long.class)
                .single())
        .isGreaterThanOrEqualTo(1L);

    UUID quarter = quarterOf(complaint, 2);
    assertThat(
            (Outcome)
                tx.execute(
                    x ->
                        scores.onScoreComputed(
                            t,
                            score(quarter, "TINH_LAI_HAU_KIEM", "HTNV", LOCKED.plusSeconds(10)))))
        .isEqualTo(Outcome.REPLACED);
    verify(recompute)
        .request(t, annual.annualProfileOf(t, YEAR, complaint, "DANG").orElseThrow(), quarter);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Filter filter(boolean linkageOnly) {
    return new Filter(
        annualPeriod, null, "DANG", linkageOnly, null, new Scope("true", List.of()), null);
  }

  @Test
  void canhBaoVaDeXuatBoTriLaiChiTaoMotViec() throws SQLException {
    TenantId t = TenantId.of(tenant);
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute(
          "UPDATE appraisal.appraisal_profile SET decider_id = '"
              + decider
              + "' WHERE person_id = '"
              + twice
              + "' AND period_bucket = "
              + (BUCKET + 4));
    }
    AnnualNotices notices = mock(AnnualNotices.class);
    PeriodApi periods = mock(PeriodApi.class);
    when(periods.findById(t, PeriodId.of(q4Period)))
        .thenReturn(Optional.of(period(q4Period, "2029-10-01", "2029-12-31")));
    when(periods.findCovering(t, "NAM", LocalDate.parse("2029-12-31")))
        .thenReturn(Optional.of(period(annualPeriod, "2029-01-01", "2029-12-31")));
    UUID taskPeriod = UUID.randomUUID();
    when(periods.findCovering(eq(t), eq("QUY"), any()))
        .thenReturn(Optional.of(period(taskPeriod, "2030-01-01", "2030-03-31")));
    AnnualSetupService flagged = new AnnualSetupService(annual, periods, null, null, notices);
    tx.execute(x -> flagged.onQuarterLocked(t, q4Period, BUCKET + 4, LOCKED));
    UUID twiceAnnual = annual.annualProfileOf(t, YEAR, twice, "DANG").orElseThrow();
    verify(notices).reassignFlagged(t, twiceAnnual, List.of(decider));
    verify(notices, times(1)).reassignFlagged(any(), any(), any());

    TaskApi tasks = mock(TaskApi.class);
    UUID task = UUID.randomUUID();
    when(tasks.openDirectedTask(eq(t), any())).thenReturn(task);
    AnnualReassignmentService proposals =
        new AnnualReassignmentService(
            annual,
            tasks,
            periods,
            null,
            java.time.Clock.fixed(Instant.parse("2030-01-15T03:00:00Z"), java.time.ZoneOffset.UTC),
            java.time.ZoneId.of("Asia/Ho_Chi_Minh"),
            30);
    AnnualReassignmentService.Caller caller =
        new AnnualReassignmentService.Caller(UUID.randomUUID(), java.util.Set.of("appraiser"));
    AnnualRatingService service =
        new AnnualRatingService(annual, new JdbcScoreSnapshotReader(JdbcClient.create(ds)));
    AnnualRow tw = row(service.page(t, filter(false), 50).rows(), twice);
    assertThatThrownBy(
            () ->
                tx.execute(
                    x -> proposals.propose(t, tw.id(), tw.rowVersion() + 1, caller, r -> {})))
        .hasMessageContaining(AnnualReassignmentService.VERSION_CONFLICT);
    AnnualReassignmentService.Proposal p =
        tx.execute(x -> proposals.propose(t, tw.id(), tw.rowVersion(), caller, r -> {}));
    assertThat(p.taskId()).isEqualTo(task);
    assertThat(p.rowVersion()).isEqualTo(tw.rowVersion() + 1);
    assertThatThrownBy(
            () -> tx.execute(x -> proposals.propose(t, tw.id(), p.rowVersion(), caller, r -> {})))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnnualReassignmentService.EXISTS));
    var captor = org.mockito.ArgumentCaptor.forClass(TaskApi.DirectedTask.class);
    verify(tasks, times(1)).openDirectedTask(eq(t), captor.capture());
    assertThat(captor.getValue().periodId()).isEqualTo(taskPeriod);
    assertThat(captor.getValue().dueDate()).isEqualTo(LocalDate.parse("2030-02-14"));
    assertThat(captor.getValue().acceptorId()).isEqualTo(decider);
    AnnualRow after = row(service.page(t, filter(false), 50).rows(), twice);
    assertThat(after.relocationProposed()).isTrue();

    AnnualRow w = row(service.page(t, filter(false), 50).rows(), weak);
    assertThatThrownBy(
            () -> tx.execute(x -> proposals.propose(t, w.id(), w.rowVersion(), caller, r -> {})))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnnualReassignmentService.NO_FLAG));
  }

  private static AnnualRow row(List<AnnualRow> rows, UUID person) {
    return rows.stream().filter(r -> r.personId().equals(person)).findFirst().orElseThrow();
  }

  /** Bốn quý luồng Đảng; quý IV vừa lập ({@code CHUA_LAP}). */
  private void seedQuarters() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      quarters(st, weak, "HTTNV", "KHTNV", "HTNV");
      quarters(st, twice, "HTTNV", "KHTNV", "KHTNV");
      quarters(st, missing, "HTTNV", null, "HTTNV");
      quarters(st, special, "HTTNV", null, "HTTNV");
      quarters(st, complaint, "HTTNV", "KHTNV", "HTTNV");
      // KHTNV ở quý I và III không liền nhau: có quý KHTNV nhưng không gắn cờ bố trí lại
      // (BR-10-04).
      quarters(st, apart, "KHTNV", "HTNV", "KHTNV");
      st.execute(
          "UPDATE appraisal.appraisal_profile SET status = 'KHIEU_NAI' WHERE tenant_id = '"
              + tenant
              + "' AND person_id = '"
              + complaint
              + "' AND period_bucket = "
              + (BUCKET + 2));
    }
  }

  private void quarters(Statement st, UUID person, String... ratings) throws SQLException {
    for (int q = 1; q <= 4; q++) {
      String rating = q <= 3 ? ratings[q - 1] : null;
      if (q <= 3 && rating == null) {
        continue;
      }
      st.execute(
          "INSERT INTO appraisal.appraisal_profile (id, tenant_id, created_by, updated_by,"
              + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id,"
              + " job_title_id, policy_pack_version_id, status, final_rating_code) VALUES"
              + " (gen_random_uuid(), '"
              + tenant
              + "', gen_random_uuid(), gen_random_uuid(), '"
              + (q == 4 ? q4Period : UUID.randomUUID())
              + "', "
              + (BUCKET + q)
              + ", '"
              + person
              + "', 'DANG', '"
              + unit
              + "', gen_random_uuid(), gen_random_uuid(), '"
              + pack
              + "', '"
              + (rating == null ? "CHUA_LAP" : "DA_KY_SO")
              + "', "
              + (rating == null ? "NULL" : "'" + rating + "'")
              + ")");
    }
  }

  private UUID quarterOf(UUID person, int q) {
    return JdbcClient.create(ds)
        .sql("SELECT id FROM appraisal.appraisal_profile WHERE period_bucket = ? AND person_id = ?")
        .params(BUCKET + q, person)
        .query(UUID.class)
        .single();
  }

  private ScoreComputed score(UUID profile, String kind, String rating, Instant at) {
    return new ScoreComputed(
        UUID.randomUUID(),
        profile,
        kind,
        new BigDecimal("85.500"),
        rating,
        new byte[32],
        new byte[32],
        at);
  }

  private PeriodSnapshot period(UUID id, String from, String to) {
    return new PeriodSnapshot(
        PeriodId.of(id),
        TenantId.of(tenant),
        "KY",
        "Kỳ",
        LocalDate.parse(from),
        LocalDate.parse(to),
        PeriodStatus.DA_KHOA);
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
