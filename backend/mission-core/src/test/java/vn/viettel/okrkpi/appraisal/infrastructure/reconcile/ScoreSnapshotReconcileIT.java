package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * R3 (DL-12) trên PostgreSQL thật: phía ảnh chụp là {@code appraisal} với vai trò {@code
 * svc_appraisal}; phía scoring là bảng {@code score_result} rút gọn ở schema riêng đọc qua nguồn
 * thứ hai (mã thật của schema {@code scoring} ở scoring-service). Khớp thì {@code KHOP}; lệch băm
 * hay thiếu kết quả thì {@code LOI} và chặn ký số đúng hồ sơ có ảnh chụp hiện hành lệch; sửa xong
 * chạy lại thì {@code DA_SUA} và gỡ chặn.
 */
class ScoreSnapshotReconcileIT {

  private static final int BUCKET = 20283;
  private static final String SCORING = "scoring_r3_it";
  private static JdbcTemplate appraisal;
  private static TenantDataSourceTransactionManager tm;
  private static JdbcTemplate scoring;
  private static ScoreSnapshotReconciler.ScoringSource source;
  private static SimpleMeterRegistry meters;

  private final MutableClock clock =
      new MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
  private TenantId tenant;
  private UUID period;
  private ScoreSnapshotReconciler reconciler;
  private ScoreReconcileGate gate;

  @BeforeAll
  static void setUp() {
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource admin = new HikariDataSource();
    admin.setMaximumPoolSize(2);
    admin.setJdbcUrl(pg.getJdbcUrl());
    admin.setUsername(pg.getUsername());
    admin.setPassword(pg.getPassword());
    JdbcTemplate a = new JdbcTemplate(admin);
    for (String t : List.of("appraisal_profile", "score_snapshot")) {
      a.queryForList(
          "SELECT appraisal.fn_create_period_partitions('appraisal." + t + "', " + BUCKET + ")");
    }
    a.execute("CREATE SCHEMA IF NOT EXISTS " + SCORING);
    a.execute(
        "CREATE TABLE IF NOT EXISTS "
            + SCORING
            + ".score_result (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, period_id uuid NOT NULL,"
            + " profile_id uuid NOT NULL, input_hash bytea NOT NULL, policy_content_hash bytea"
            + " NOT NULL, status varchar(8) NOT NULL)");
    scoring = a;
    source =
        new ScoreSnapshotReconciler.ScoringSource(
            a, new DataSourceTransactionManager(admin), SCORING);

    HikariDataSource svc = new HikariDataSource();
    svc.setMaximumPoolSize(3);
    svc.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=appraisal");
    svc.setUsername("svc_appraisal");
    svc.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    appraisal = new JdbcTemplate(svc);
    tm = new TenantDataSourceTransactionManager(svc, new TenantProperties(null, null));
    meters = new SimpleMeterRegistry();
  }

  @BeforeEach
  void each() {
    tenant = TenantId.of(UUID.randomUUID());
    period = UUID.randomUUID();
    ObjectMapper json = new ObjectMapper();
    reconciler =
        new ScoreSnapshotReconciler(
            appraisal, tm, source, new ReconcileMetrics(meters, clock), json, clock);
    gate = new ScoreReconcileGate(appraisal, tm, json);
  }

  @Test
  void moiAnhChupCoKetQuaCungBamThiKhop() {
    UUID p1 = profile();
    UUID p2 = profile();
    snapshot(p1, result(p1, "aa", "OK"), "aa", true);
    snapshot(p2, result(p2, "bb", "OK"), "bb", true);

    var out = reconciler.reconcile(tenant, period);
    assertThat(out.status()).isEqualTo(RunStatus.KHOP);
    assertThat(out.checked()).isEqualTo(2);
    ReconcileRun run = run(out.runId());
    assertThat(run.bucketsTotal()).isEqualTo(256);
    assertThat(run.bucketsMismatch()).isZero();
    assertThat(blocked(p1)).isFalse();

    // Lịch: kỳ có ảnh chụp mới được chọn; chạy lại cùng phút không ghi trùng.
    clock.advance(Duration.ofMinutes(30));
    snapshot(profile(), result(UUID.randomUUID(), "cc", "OK"), "cc", true);
    assertThat(job().runTenant(tenant)).isEqualTo(1);
    assertThat(reconciler.reconcile(tenant, period).status()).isEqualTo(RunStatus.DANG_CHAY);
  }

  @Test
  void lechBamHayThieuKetQuaThiChanKySoDungHoSoRoiGoChanKhiSua() {
    UUID ok = profile();
    UUID wrong = profile();
    UUID lost = profile();
    UUID old = profile();
    snapshot(ok, result(ok, "a1", "OK"), "a1", true);
    UUID wrongResult = result(wrong, "b1", "OK");
    snapshot(wrong, wrongResult, "b2", true);
    snapshot(lost, UUID.randomUUID(), "c1", true);
    // Ảnh chụp đã bị thay thế mà lệch: ghi nhận nhưng không chặn ký.
    snapshot(old, result(old, "d1", "LOI"), "d1", false);

    var out = reconciler.reconcile(tenant, period);
    assertThat(out.status()).isEqualTo(RunStatus.LOI);
    assertThat(out.blocked()).containsExactlyInAnyOrder(wrong, lost);
    ReconcileRun run = run(out.runId());
    assertThat(run.bucketsMismatch()).isBetween(1, 3);
    assertThat(run.detail())
        .contains("\"LECH_BAM\": 1")
        .contains("\"THIEU_KET_QUA\": 1")
        .contains("\"KET_QUA_LOI\": 1")
        .contains("\"superseded\": 1")
        .contains(wrong.toString());
    assertThat(blocked(wrong)).isTrue();
    assertThat(blocked(lost)).isTrue();
    assertThat(blocked(ok)).isFalse();
    assertThat(blocked(old)).isFalse();
    assertThat(inTenant(() -> gate.anyBlocked(List.of(ok, lost)))).isTrue();
    assertThat(
            meters
                .find(ReconcileMetrics.MISMATCHES)
                .tag("reconcile", ScoreSnapshotReconciler.JOB)
                .counter()
                .count())
        .isGreaterThanOrEqualTo(3);

    // Người vận hành sửa nguồn theo runbook: phía scoring khớp lại, ảnh chụp thiếu được nối kết
    // quả.
    scoring.update(
        "UPDATE " + SCORING + ".score_result SET input_hash = ? WHERE id = ?",
        hash("b2"),
        wrongResult);
    UUID lostResult = lostResultOf(lost);
    scoring.update(
        "INSERT INTO " + SCORING + ".score_result VALUES (?, ?, ?, ?, ?, ?, 'OK')",
        lostResult,
        tenant.value(),
        period,
        lost,
        hash("c1"),
        hash("pp"));
    scoring.update(
        "UPDATE " + SCORING + ".score_result SET status = 'OK' WHERE profile_id = ?", old);
    clock.advance(Duration.ofMinutes(30));
    assertThat(job().runTenant(tenant)).isEqualTo(1);
    ReconcileRun again =
        run(ScoreSnapshotReconciler.runId(tenant.value(), period, clock.instant()));
    assertThat(again.status()).isEqualTo(RunStatus.DA_SUA);
    assertThat(again.rowsRepaired()).isEqualTo(2);
    assertThat(blocked(wrong)).isFalse();
    assertThat(blocked(lost)).isFalse();
  }

  @Test
  void khongDocDuocPhiaScoringThiLoiMaKhongChan() {
    UUID p = profile();
    snapshot(p, UUID.randomUUID(), "aa", true);
    ScoreSnapshotReconciler broken =
        new ScoreSnapshotReconciler(
            appraisal,
            tm,
            new ScoreSnapshotReconciler.ScoringSource(scoring, source.tm(), "khong_co_schema"),
            new ReconcileMetrics(meters, clock),
            new ObjectMapper(),
            clock);
    var out = broken.reconcile(tenant, period);
    assertThat(out.status()).isEqualTo(RunStatus.LOI);
    assertThat(run(out.runId()).detail()).contains("KHONG_DOC_DUOC");
    assertThat(blocked(p)).isFalse();
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ScoreSnapshotReconcileJob job() {
    return new ScoreSnapshotReconcileJob(
        reconciler,
        appraisal,
        tm,
        new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(vn.viettel.okrkpi.shared.routing.TenantDirectory.class),
        "",
        Duration.ofDays(35),
        clock);
  }

  private boolean blocked(UUID profile) {
    return inTenant(() -> gate.blocked(profile));
  }

  private UUID profile() {
    UUID id = UUID.randomUUID();
    inTenant(
        () ->
            appraisal.update(
                "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
                    + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
                    + " policy_pack_version_id) VALUES (?, gen_random_uuid(), gen_random_uuid(), ?,"
                    + " ?, gen_random_uuid(), 'DANG', gen_random_uuid(), gen_random_uuid(),"
                    + " gen_random_uuid(), gen_random_uuid())",
                id,
                period,
                BUCKET));
    return id;
  }

  private UUID result(UUID profile, String input, String status) {
    UUID id = UUID.randomUUID();
    scoring.update(
        "INSERT INTO " + SCORING + ".score_result VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        tenant.value(),
        period,
        profile,
        hash(input),
        hash("pp"),
        status);
    return id;
  }

  private void snapshot(UUID profile, UUID resultId, String input, boolean current) {
    inTenant(
        () ->
            appraisal.update(
                "INSERT INTO appraisal.score_snapshot (id, created_by, updated_by, period_id,"
                    + " period_bucket, profile_id, flow, snapshot_kind, score_result_id,"
                    + " policy_pack_version_id, policy_content_hash, policy_version, input_hash,"
                    + " total_score, auto_rating_code, rule_checks, group_scores_json, computed_at,"
                    + " snapshot_hash, superseded_by_id) VALUES (gen_random_uuid(),"
                    + " gen_random_uuid(), gen_random_uuid(), ?, ?, ?, 'DANG', 'CHOT_KY', ?,"
                    + " gen_random_uuid(), ?, 'v1', ?, 80, 'HTTNV', '[]', '{}', now(), ?, ?)",
                period,
                BUCKET,
                profile,
                resultId,
                hash("pp"),
                hash(input),
                hash("ss"),
                current ? null : UUID.randomUUID()));
  }

  private UUID lostResultOf(UUID profile) {
    return inTenant(
        () ->
            appraisal.queryForObject(
                "SELECT score_result_id FROM appraisal.score_snapshot WHERE profile_id = ?",
                UUID.class,
                profile));
  }

  private ReconcileRun run(UUID id) {
    return inTenant(() -> new JdbcReconcileRunStore(appraisal, "appraisal").find(id).orElseThrow());
  }

  private static byte[] hash(String s) {
    byte[] out = new byte[32];
    byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    System.arraycopy(b, 0, out, 0, Math.min(b.length, 32));
    return out;
  }

  private <T> T inTenant(Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              new org.springframework.transaction.support.TransactionTemplate(tm)
                  .execute(
                      st -> {
                        try {
                          return work.call();
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
