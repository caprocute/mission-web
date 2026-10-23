package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.application.ScoreSimulationService;
import vn.viettel.okrkpi.scoring.application.ScoreTraceService;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.ProfileScorer;
import vn.viettel.okrkpi.scoring.application.policy.RatingClassifier;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.application.port.Designations;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.DelaySegment;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * Tính thử và xem trước điểm trên PostgreSQL 17 thật (DV-07-08): kết quả gốc ghi bằng đường ghi của
 * DV-07-01, đọc lại bằng {@code svc_scoring} dưới RLS; tính thử dùng phiên bản gói gốc dù kỳ đã
 * ghim phiên bản mới, không ghi thêm dòng nào vào {@code score_result}, {@code score_trace},
 * outbox.
 */
class ScoreSimulationIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final int BUCKET = 20272;
  private static final UUID TENANT_A = UUID.fromString("0192f1a2-0000-7000-8000-00000000a008");
  private static final UUID TENANT_B = UUID.fromString("0192f1a2-0000-7000-8000-00000000b008");
  private static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-0000000000aa");
  private static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000008bb");
  private static final UUID TASK = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f8c01");

  /** Thuộc tính hồ sơ luồng Đảng (như {@code RatingClassifierTest}). */
  private static final Map<String, Object> PARTY =
      Map.of(
          "ty_le_O", 1.0,
          "ty_le_dap_ung_tieu_chi_chung", 1.0,
          "giai_phap_dot_pha_da_kiem_chung", true,
          "loai_tru", false,
          "la_nguoi_dung_dau", false,
          "tap_the_dat_05_chi_tieu", true,
          "tap_the_dat_tat_ca_O", true,
          "ty_le_don_vi_khtnv", 0.0,
          "giu_chuc_vu", false,
          "xep_loai_nha_nuoc", "");

  private static PostgreSQLContainer<?> pg;
  private static ScoreRecorder recorder;
  private static ScoreSimulationService service;
  private static TransactionTemplate tx;
  private static FlowableDecisionTables tables;
  private static CompiledPolicy original;
  private static ProfileScorer scorer;
  private static PolicyPackCache cache;

  @BeforeAll
  static void start() throws SQLException {
    pg = TestPostgres.container("td_scoring_simulation");
    pg.start();
    Map<String, String> env = new java.util.HashMap<>();
    for (String role : RoleBootstrapPlan.roles("scoring")) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin = admin()) {
      DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
      bootstrap.applySchemas(admin, List.of("scoring"));
      bootstrap.setTenancyModel(admin, "pool");
    }
    new SchemaMigrator(MigrationOptions.defaults())
        .migrate(
            pg.getJdbcUrl(),
            "scoring",
            new MigrationCredentials.Credentials("mig_scoring", PASSWORD));
    try (Connection admin = admin();
        Statement st = admin.createStatement()) {
      for (String t : List.of("score_job", "score_input_v", "score_result", "score_trace")) {
        st.execute(
            "SELECT scoring.fn_create_period_partitions('scoring." + t + "', " + BUCKET + ")");
      }
    }
    DriverManagerDataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), "svc_scoring", PASSWORD);
    JdbcTemplate svc = new JdbcTemplate(ds);
    tx =
        new TransactionTemplate(
            new TenantDataSourceTransactionManager(ds, TenantProperties.defaults()));
    OutboxPayloadSerializer serializer =
        (topic, payload) -> new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    OutboxWriter outbox =
        new OutboxWriter(svc, "scoring", serializer, new ObjectMapper(), Clock.systemUtc());
    recorder =
        new ScoreRecorder(
            new JdbcScoreJobStore(JdbcClient.create(ds)),
            new CopyScoreResultStore(svc),
            new OutboxScoreEvents(outbox));
    tables = new FlowableDecisionTables();
    PolicyCompiler compiler =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables);
    PolicyContent old = SamplePolicyPack.content();
    // Phiên bản mới phát hành sau khi chốt: C luôn bằng 1; nếu tính thử dùng nhầm thì "trước" sai.
    PolicyContent newer =
        SamplePolicyPack.content(
            old.pinned().packId(), UUID.randomUUID(), "2.2.0", Map.of("kpi_C", "1.0"));
    original = compiler.compile(old);
    scorer = new ProfileScorer(new ScoreCalculator(), new RatingClassifier());
    PolicyPackSource source =
        new PolicyPackSource() {
          @Override
          public Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
            return Optional.of(newer.pinned());
          }

          @Override
          public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
            return versionId.equals(old.pinned().versionId()) ? old : newer;
          }
        };
    cache = new PolicyPackCache(source, compiler, 8);
    service =
        new ScoreSimulationService(
            new JdbcStoredScores(JdbcClient.create(ds)),
            cache,
            scorer,
            Designations.NONE,
            new ScoreSimulationService.Guard());
  }

  @AfterAll
  static void stop() {
    tables.destroy();
    pg.stop();
  }

  @Test
  void tinhThuDungPhienBanGocVaKhongGhiGi() throws SQLException {
    UUID profile = UUID.randomUUID();
    ComputedScore stored = record(TENANT_A, profile, TASK);
    long results = adminLong("SELECT count(*) FROM scoring.score_result");
    long steps = adminLong("SELECT count(*) FROM scoring.score_trace");
    long events = adminLong("SELECT count(*) FROM scoring.outbox_event");
    long inputs = adminLong("SELECT count(*) FROM scoring.score_input_v");

    ScoreSimulationService.Simulation s =
        inTenant(
            TENANT_A,
            () ->
                service.simulate(
                    profile,
                    List.of(new ScoreSimulationService.Adjustment("C", TASK, BigDecimal.ONE))));
    assertThat(s.policyVersion()).isEqualTo("2.1.0");
    assertThat(s.before().totalScore()).isEqualByComparingTo(stored.totalScore());
    assertThat(s.before().ratingCode()).isEqualTo(stored.autoRatingCode());
    assertThat(s.after().totalScore()).isGreaterThan(s.before().totalScore());
    assertThat(s.explanation()).startsWith("Sau điều chỉnh, điểm tăng từ ");
    // Kỳ đã ghim phiên bản mới nhưng bộ đệm chỉ nạp đúng phiên bản theo băm của kết quả gốc.
    assertThat(cache.cached(original.contentHash())).isPresent();

    assertThat(adminLong("SELECT count(*) FROM scoring.score_result")).isEqualTo(results);
    assertThat(adminLong("SELECT count(*) FROM scoring.score_trace")).isEqualTo(steps);
    assertThat(adminLong("SELECT count(*) FROM scoring.outbox_event")).isEqualTo(events);
    assertThat(adminLong("SELECT count(*) FROM scoring.score_input_v")).isEqualTo(inputs);

    // Biến cấp hồ sơ theo inputs của gói: tỷ lệ đáp ứng tiêu chí chung trong 0..1.
    ScoreSimulationService.Simulation criteria =
        inTenant(
            TENANT_A,
            () ->
                service.simulate(
                    profile,
                    List.of(
                        new ScoreSimulationService.Adjustment(
                            "ty_le_dap_ung_tieu_chi_chung", null, new BigDecimal("0.5")))));
    assertThat(criteria.before()).isEqualTo(s.before());
  }

  @Test
  void dieuChinhNgoaiMienBi422VaTenantKhacKhongThay() {
    UUID profile = UUID.randomUUID();
    record(TENANT_A, profile, TASK);
    assertThatThrownBy(
            () ->
                inTenant(
                    TENANT_A,
                    () ->
                        service.simulate(
                            profile,
                            List.of(
                                new ScoreSimulationService.Adjustment(
                                    "C", TASK, new BigDecimal("1.5")),
                                new ScoreSimulationService.Adjustment(
                                    "B", UUID.randomUUID(), BigDecimal.ONE),
                                new ScoreSimulationService.Adjustment(
                                    "ty_le_O", null, new BigDecimal("2")),
                                new ScoreSimulationService.Adjustment(
                                    "khong_co", null, BigDecimal.ONE)))))
        .isInstanceOfSatisfying(
            ScoreSimulationService.Rejected.class,
            e ->
                assertThat(e.issues())
                    .extracting(ScoreSimulationService.Issue::field)
                    .containsExactly(
                        "adjustments[0].newValue",
                        "adjustments[1].taskId",
                        "adjustments[2].newValue",
                        "adjustments[3].component"));
    assertThatThrownBy(
            () ->
                inTenant(
                    TENANT_B,
                    () ->
                        service.simulate(
                            profile,
                            List.of(
                                new ScoreSimulationService.Adjustment("C", TASK, BigDecimal.ONE)))))
        .isInstanceOf(ScoreTraceService.TraceNotFound.class);
  }

  @Test
  void xemTruocNhiemVuVaTongHopMoLai() {
    UUID profile = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    record(TENANT_A, profile, task);
    ScoreSimulationService.Preview p =
        inTenant(
            TENANT_A,
            () ->
                service.preview(
                    new ScoreSimulationService.PreviewRequest(
                        task, new BigDecimal("0.75"), "NOI_DUNG_KHONG_COT_LOI", false)));
    assertThat(p.a()).isEqualByComparingTo("1");
    assertThat(p.b()).isEqualByComparingTo("0.75");
    assertThat(p.c()).isEqualByComparingTo("0.9");
    assertThat(p.kpi()).isEqualByComparingTo("0.883");
    assertThat(p.policy()).isEqualTo("2.1.0");
    assertThatThrownBy(
            () ->
                inTenant(
                    TENANT_A,
                    () ->
                        service.preview(
                            new ScoreSimulationService.PreviewRequest(
                                UUID.randomUUID(), BigDecimal.ONE, null, null))))
        .isInstanceOf(ScoreSimulationService.Rejected.class);

    ScoreSimulationService.Impact impact =
        inTenant(
            TENANT_A,
            () ->
                service.simulateReopen(
                    List.of(
                        new ScoreSimulationService.ProfileAdjustments(
                            profile,
                            List.of(
                                new ScoreSimulationService.Adjustment("C", task, BigDecimal.ONE))),
                        new ScoreSimulationService.ProfileAdjustments(
                            UUID.randomUUID(), List.of()))));
    assertThat(impact.profiles()).isEqualTo(1);
    assertThat(impact.scoreChanged()).isEqualTo(1);
  }

  /** Ghi một kết quả gốc: một nhiệm vụ then chốt chậm 2 ngày làm việc chủ quan (C = 0,9). */
  private static ComputedScore record(UUID tenant, UUID profile, UUID task) {
    UUID person = UUID.randomUUID();
    TaskInput nv =
        new TaskInput(
            task,
            "NV-1",
            "THEN_CHOT",
            1,
            new BigDecimal("30"),
            false,
            List.of(
                new ProductInput(
                    UUID.randomUUID(), ProductInput.CONFIRMED, BigDecimal.ONE, false, 0)),
            List.of(new DelaySegment(2, 2, DelaySegment.Cause.CHU_QUAN)));
    ScoringInput input = new ScoringInput(profile, person, "DANG", List.of(nv), PARTY);
    ComputedScore score = scorer.score(Ids.newUuidV7(), original, input);
    UUID job = openJob(tenant);
    inTenant(
        tenant,
        () ->
            recorder.record(
                new ScoreBatch(
                    tenant,
                    job,
                    ScoreJobKind.TAM_TINH,
                    PERIOD,
                    BUCKET,
                    original.pinned().versionId(),
                    original.pinned().contentHashBytes(),
                    "scoring-service:0.1.0",
                    ACTOR,
                    Instant.now(),
                    List.of(score))));
    return score;
  }

  private static UUID openJob(UUID tenant) {
    ScoreJob job =
        new ScoreJob(
            tenant,
            Ids.newUuidV7(),
            PERIOD,
            BUCKET,
            ScoreJobKind.TAM_TINH,
            CanonicalDoc.of(Map.of("ho_so", 1)),
            original.pinned().versionId(),
            null,
            1,
            ACTOR);
    return inTenant(tenant, () -> recorder.open(job));
  }

  private static <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
    try {
      return TenantContext.call(TenantId.of(tenant), () -> tx.execute(s -> work.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static long adminLong(String sql) {
    try (Connection admin = admin();
        Statement st = admin.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
