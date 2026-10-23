package vn.viettel.okrkpi.scoring.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.scoring.application.ScoreRecorder;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;
import vn.viettel.okrkpi.scoring.infrastructure.events.OutboxScoreEvents;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.CopyScoreResultStore;
import vn.viettel.okrkpi.scoring.infrastructure.persistence.JdbcScoreJobStore;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * R4 (DL-12) trên PostgreSQL 17 thật với {@code svc_scoring}: lấy mẫu mở tác vụ {@code
 * KIEM_TRA_TAI_LAP} cùng phiên bản quy tắc, chờ bộ máy tính, so {@code result_hash}; lệch thì
 * {@code LOI} có mã kết quả và phiên bản bộ máy tính, quá hạn chưa tính xong cũng {@code LOI}. Bộ
 * máy tính (DV-07-06) được giả lập bằng {@link ScoreRecorder} ghi kết quả vào tác vụ kiểm tra.
 */
class ScoreRecomputeReconcileIT {

  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final int BUCKET = 20283;
  private static final UUID ACTOR = UUID.fromString("0192f1a2-0000-7000-8000-0000000000aa");
  private static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000bb");
  private static final UUID PPV = UUID.fromString("0192f1a2-0000-7000-8000-0000000000cc");

  private static PostgreSQLContainer<?> pg;
  private static ScoreRecorder recorder;
  private static JdbcTemplate svc;
  private static TenantDataSourceTransactionManager tm;
  private static JdbcScoreJobStore jobs;

  private final MutableClock clock = new MutableClock(Instant.now());

  @BeforeAll
  static void start() throws SQLException {
    pg = TestPostgres.container("td_scoring_r4");
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
    svc = new JdbcTemplate(ds);
    tm = new TenantDataSourceTransactionManager(ds, TenantProperties.defaults());
    OutboxPayloadSerializer serializer =
        (topic, payload) -> new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    OutboxWriter outbox =
        new OutboxWriter(
            svc,
            "scoring",
            serializer,
            new ObjectMapper(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    jobs = new JdbcScoreJobStore(JdbcClient.create(ds));
    recorder =
        new ScoreRecorder(jobs, new CopyScoreResultStore(svc), new OutboxScoreEvents(outbox));
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @Test
  void tinhLaiLechBamThiLoiCoMaKetQuaVaPhienBan() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ScoreRecomputeReconciler r = reconciler(meters, 1.0);
    UUID original = openJob(tenant, ScoreJobKind.CHOT_KY, 3, null);
    List<ComputedScore> scores = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      scores.add(score(UUID.randomUUID(), "{\"i\":" + i + "}", "80.000"));
    }
    record(tenant, original, ScoreJobKind.CHOT_KY, "scoring-service:0.1.0", scores);

    assertThat(r.sample(tenant)).isEqualTo(3);
    assertThat(r.sample(tenant)).as("cùng tuần không mở lại").isZero();
    ReconcileRun run = run(tenant);
    assertThat(run.status()).isEqualTo(RunStatus.DANG_CHAY);
    UUID check = checkJob(tenant, original);
    assertThat(r.evaluate(tenant)).as("bộ máy tính chưa xong thì chờ").isZero();

    // Bộ máy tính tính lại: hai hồ sơ cùng kết quả, một hồ sơ khác điểm ở phiên bản mới.
    List<ComputedScore> again = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ComputedScore s = scores.get(i);
      again.add(score(s.profileId(), s.input().json(), i == 2 ? "79.500" : "80.000"));
    }
    record(tenant, check, ScoreJobKind.KIEM_TRA_TAI_LAP, "scoring-service:0.2.0", again);

    assertThat(r.evaluate(tenant)).isEqualTo(1);
    ReconcileRun done = run(tenant);
    assertThat(done.status()).isEqualTo(RunStatus.LOI);
    assertThat(done.detail())
        .contains("\"checked\": 3")
        .contains("\"mismatched\": 1")
        .contains(scores.get(2).resultId().toString())
        .contains("scoring-service:0.2.0");
    assertThat(
            meters
                .find(ReconcileMetrics.MISMATCHES)
                .tag("reconcile", ScoreRecomputeReconciler.JOB)
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void khopThiKhopVaQuaHanChuaTinhThiLoi() {
    TenantId a = TenantId.of(UUID.randomUUID());
    ScoreRecomputeReconciler r = reconciler(new SimpleMeterRegistry(), 1.0);
    UUID original = openJob(a, ScoreJobKind.CHOT_KY, 1, null);
    ComputedScore s = score(UUID.randomUUID(), "{\"k\":1}", "90.000");
    record(a, original, ScoreJobKind.CHOT_KY, "scoring-service:0.1.0", List.of(s));
    r.sample(a);
    record(
        a,
        checkJob(a, original),
        ScoreJobKind.KIEM_TRA_TAI_LAP,
        "scoring-service:0.1.0",
        List.of(score(s.profileId(), s.input().json(), "90.000")));
    r.evaluate(a);
    assertThat(run(a).status()).isEqualTo(RunStatus.KHOP);

    TenantId b = TenantId.of(UUID.randomUUID());
    UUID other = openJob(b, ScoreJobKind.CHOT_KY, 1, null);
    record(
        b,
        other,
        ScoreJobKind.CHOT_KY,
        "scoring-service:0.1.0",
        List.of(score(UUID.randomUUID(), "{\"k\":2}", "70.000")));
    r.sample(b);
    clock.advance(Duration.ofDays(3));
    assertThat(r.evaluate(b)).isEqualTo(1);
    assertThat(run(b, clock.startedWeekOf()).detail()).contains("CHUA_TINH_LAI_XONG");
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ScoreRecomputeReconciler reconciler(SimpleMeterRegistry meters, double rate) {
    return new ScoreRecomputeReconciler(
        svc,
        tm,
        jobs,
        new ReconcileMetrics(meters, clock),
        new ObjectMapper(),
        clock,
        rate,
        100,
        Duration.ofDays(400),
        Duration.ofDays(2));
  }

  private ReconcileRun run(TenantId t) {
    return run(t, ScoreRecomputeReconciler.week(clock.instant()));
  }

  private ReconcileRun run(TenantId t, String week) {
    return inTenant(
        t,
        () ->
            new JdbcReconcileRunStore(svc, "scoring")
                .find(ScoreRecomputeReconciler.runId(t.value(), week))
                .orElseThrow());
  }

  private UUID checkJob(TenantId t, UUID original) {
    return inTenant(
        t,
        () ->
            svc.queryForObject(
                "SELECT id FROM scoring.score_job WHERE job_kind = 'KIEM_TRA_TAI_LAP'"
                    + " AND scope ->> 'recheckOf' = ?",
                UUID.class,
                original.toString()));
  }

  private UUID openJob(TenantId t, ScoreJobKind kind, int total, UUID trigger) {
    ScoreJob job =
        new ScoreJob(
            t.value(),
            Ids.newUuidV7(),
            PERIOD,
            BUCKET,
            kind,
            CanonicalDoc.of(Map.of("don_vi", List.of("DV-01"))),
            PPV,
            trigger,
            total,
            ACTOR);
    return inTenant(t, () -> recorder.open(job));
  }

  private void record(
      TenantId t, UUID job, ScoreJobKind kind, String engine, List<ComputedScore> items) {
    inTenant(
        t,
        () ->
            recorder.record(
                new ScoreBatch(
                    t.value(),
                    job,
                    kind,
                    PERIOD,
                    BUCKET,
                    PPV,
                    new byte[32],
                    engine,
                    ACTOR,
                    clock.instant(),
                    items)));
  }

  private static ComputedScore score(UUID profile, String input, String total) {
    return new ComputedScore(
        Ids.newUuidV7(),
        UUID.randomUUID(),
        "DANG",
        profile,
        CanonicalDoc.parse(input),
        CanonicalDoc.of(Map.of("tong_diem", total, "muc", "HTTNV")),
        new BigDecimal(total),
        "HTTNV",
        ScoreStatus.OK,
        List.of());
  }

  private static <T> T inTenant(TenantId t, java.util.function.Supplier<T> work) {
    try {
      return TenantContext.call(t, () -> new TransactionTemplate(tm).execute(s -> work.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static final class MutableClock extends java.time.Clock {
    private final Instant started;
    private Instant now;

    MutableClock(Instant now) {
      this.started = now;
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    String startedWeekOf() {
      return ScoreRecomputeReconciler.week(started);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
