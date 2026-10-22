package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.policy.ScoreCalculator;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
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
 * Chuỗi tính và đọc vết trên PostgreSQL 17 thật (DV-07-03): tính hồ sơ theo gói mẫu, ghi lô bằng
 * đường ghi của DV-07-01, đọc lại vết tính bằng {@code svc_scoring} dưới RLS; kết quả mới nhất được
 * chọn, lọc theo luồng, tenant khác không thấy.
 */
class ScoreTraceIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final int BUCKET = 20271;
  private static final UUID TENANT_A = UUID.fromString("0192f1a2-0000-7000-8000-00000000a002");
  private static final UUID TENANT_B = UUID.fromString("0192f1a2-0000-7000-8000-00000000b002");
  private static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-0000000000aa");
  private static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000bb");
  private static final UUID PPV = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4b20");

  private static PostgreSQLContainer<?> pg;
  private static ScoreRecorder recorder;
  private static JdbcScoreTraceStore store;
  private static TransactionTemplate tx;
  private static FlowableDecisionTables tables;
  private static CompiledPolicy policy;

  @BeforeAll
  static void start() throws SQLException {
    pg = TestPostgres.container("td_scoring_trace");
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
    store = new JdbcScoreTraceStore(JdbcClient.create(ds));
    tables = new FlowableDecisionTables();
    policy =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables)
            .compile(SamplePolicyPack.content());
  }

  @AfterAll
  static void stop() {
    tables.destroy();
    pg.stop();
  }

  @Test
  void ghiVaDocLaiVetTinhCuaKetQuaMoiNhat() {
    UUID profile = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    ScoreCalculator calculator = new ScoreCalculator();
    ScoreCalculator.Calculation older = calculator.calculate(policy, input(profile, person, "0.5"));
    ScoreCalculator.Calculation latest = calculator.calculate(policy, input(profile, person, "1"));
    UUID job = openJob(TENANT_A);
    ComputedScore first = computed(person, profile, "DANG", older);
    inTenant(TENANT_A, () -> recorder.record(batch(TENANT_A, job, List.of(first))));
    ComputedScore second = computed(person, profile, "DANG", latest);
    inTenant(TENANT_A, () -> recorder.record(batch(TENANT_A, job, List.of(second))));

    ScoreTrace trace = inTenant(TENANT_A, () -> store.latest(profile, "DANG")).orElseThrow();
    assertThat(trace.resultId()).isEqualTo(second.resultId());
    assertThat(trace.tenantId()).isEqualTo(TENANT_A);
    assertThat(trace.personId()).isEqualTo(person);
    assertThat(trace.policyVersion()).isEqualTo("2.1.0");
    assertThat(trace.engineVersion()).isEqualTo("scoring-service:0.1.0");
    assertThat(trace.steps()).isEqualTo(latest.trace());
    assertThat(inTenant(TENANT_A, () -> store.latest(profile, null))).isPresent();
    assertThat(inTenant(TENANT_A, () -> store.latest(profile, "NHA_NUOC"))).isEmpty();
    assertThat(inTenant(TENANT_B, () -> store.latest(profile, "DANG"))).isEmpty();
    assertThat(inTenant(TENANT_A, () -> store.latest(UUID.randomUUID(), null))).isEmpty();
  }

  private static ScoringInput input(UUID profile, UUID person, String quality) {
    TaskInput task =
        new TaskInput(
            UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5c01"),
            "NV-1",
            "THEN_CHOT",
            1,
            new BigDecimal("30"),
            false,
            List.of(
                new ProductInput(
                    UUID.randomUUID(), ProductInput.CONFIRMED, new BigDecimal(quality), false, 0)),
            List.of());
    return new ScoringInput(
        profile, person, "DANG", List.of(task), Map.of("ty_le_dap_ung_tieu_chi_chung", 1.0));
  }

  private static ComputedScore computed(
      UUID person, UUID profile, String flow, ScoreCalculator.Calculation c) {
    // Mức tự động do DV-07-05 xếp; ở đây chỉ cần giá trị hợp lệ để ghi kết quả OK.
    return new ComputedScore(
        Ids.newUuidV7(),
        person,
        flow,
        profile,
        c.input(),
        c.result(),
        c.totalScore().setScale(3),
        "HTNV",
        ScoreStatus.OK,
        c.trace());
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
            PPV,
            null,
            2,
            ACTOR);
    return inTenant(tenant, () -> recorder.open(job));
  }

  private static ScoreBatch batch(UUID tenant, UUID job, List<ComputedScore> items) {
    return new ScoreBatch(
        tenant,
        job,
        ScoreJobKind.TAM_TINH,
        PERIOD,
        BUCKET,
        PPV,
        policy.pinned().contentHashBytes(),
        "scoring-service:0.1.0",
        ACTOR,
        Instant.now(),
        items);
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

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }
}
