package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.OutboxKafkaReconciler.OutboxSchema;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * R5 (DL-12) với PostgreSQL thật (schema {@code appraisal}, vai trò {@code svc_appraisal}) và
 * ClickHouse thật (bảng sự kiện rút gọn cùng engine {@code ReplacingMergeTree(_version,
 * _is_deleted)}): tổng kiểm hai phía trùng công thức; thiếu dòng ở kho phân tích lần đầu chỉ nghi,
 * lần sau vẫn lệch thì {@code LOI} kèm phân vùng cần nạp lại; dòng đã xoá ở kho không tính; không
 * đọc được ClickHouse thì {@code LOI}.
 */
class AnalyticsReconcileIT {

  private static final int BUCKET = 20284;
  private static final String DB = "analytics_r5";
  private static final GenericContainer<?> CH =
      new GenericContainer<>("mirror.gcr.io/clickhouse/clickhouse-server:25.8")
          .withEnv("CLICKHOUSE_USER", "doisoat")
          .withEnv("CLICKHOUSE_PASSWORD", "doisoat-thu")
          .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
          .withExposedPorts(8123)
          .waitingFor(Wait.forHttp("/ping").forPort(8123));
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static OutboxSchema appraisal;
  private static URI base;

  private final MutableClock clock = new MutableClock(Instant.now());

  @BeforeAll
  static void setUp() throws Exception {
    CH.start();
    base = URI.create("http://" + CH.getHost() + ":" + CH.getMappedPort(8123));
    ch("CREATE DATABASE IF NOT EXISTS " + DB);
    ch(
        "CREATE TABLE IF NOT EXISTS "
            + DB
            + ".fact_appraisal_profile (tenant_id UUID, period_bucket Int32, id UUID,"
            + " _version UInt64, _is_deleted UInt8) ENGINE = ReplacingMergeTree(_version, _is_deleted)"
            + " PARTITION BY period_bucket ORDER BY (tenant_id, id)");
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource admin = new HikariDataSource();
    admin.setJdbcUrl(pg.getJdbcUrl());
    admin.setUsername(pg.getUsername());
    admin.setPassword(pg.getPassword());
    new JdbcTemplate(admin)
        .queryForList(
            "SELECT appraisal.fn_create_period_partitions('appraisal.appraisal_profile', "
                + BUCKET
                + ")");
    admin.close();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(3);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=appraisal");
    ds.setUsername("svc_appraisal");
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    appraisal =
        new OutboxSchema(
            "appraisal",
            new JdbcTemplate(ds),
            new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null)));
  }

  @AfterAll
  static void stop() {
    CH.stop();
  }

  @Test
  void haiPhiaTrungThiKhopThieuDongHaiLanThiLoi() throws Exception {
    TenantId t = TenantId.of(UUID.randomUUID());
    UUID a = profile(t);
    UUID b = profile(t);
    UUID c = profile(t);
    fact(t, a, 1, 0);
    fact(t, b, 1, 0);
    fact(t, c, 1, 0);
    // Dòng đã xoá ở kho phân tích không tính.
    fact(t, UUID.randomUUID(), 2, 1);
    AnalyticsReconciler r = reconciler(base);

    var first = r.run(List.of(t));
    String firstDetail = run(t).detail();
    assertThat(first)
        .as(firstDetail)
        .singleElement()
        .extracting(AnalyticsReconciler.Outcome::status)
        .isEqualTo(RunStatus.KHOP);
    assertThat(run(t).detail()).doesNotContain("suspects");

    // CDC rơi một dòng mới: giờ đầu chỉ nghi, giờ sau vẫn lệch thì LOI.
    profile(t);
    clock.advance(Duration.ofHours(1));
    assertThat(r.run(List.of(t)).get(0).status()).isEqualTo(RunStatus.KHOP);
    assertThat(run(t).detail()).contains("\"suspects\"").contains("\"pgRows\": 4");
    clock.advance(Duration.ofHours(1));
    var third = r.run(List.of(t)).get(0);
    assertThat(third.status()).isEqualTo(RunStatus.LOI);
    assertThat(third.mismatched()).isEqualTo(1);
    ReconcileRun loi = run(t);
    assertThat(loi.bucketsMismatch()).isEqualTo(1);
    assertThat(loi.detail())
        .contains("\"confirmed\"")
        .contains("\"fact\": \"fact_appraisal_profile\"")
        .contains("\"periodBucket\": " + BUCKET);
    // Chạy lại cùng giờ không ghi trùng.
    assertThat(r.run(List.of(t))).isEmpty();
  }

  @Test
  void khongDocDuocClickHouseThiLoi() {
    TenantId t = TenantId.of(UUID.randomUUID());
    profile(t);
    var out = reconciler(URI.create("http://127.0.0.1:1")).run(List.of(t));
    assertThat(out)
        .singleElement()
        .extracting(AnalyticsReconciler.Outcome::status)
        .isEqualTo(RunStatus.LOI);
    assertThat(run(t).detail()).contains("KHONG_DOC_DUOC_CLICKHOUSE");
  }

  // ---------------------------------------------------------------- hỗ trợ

  private AnalyticsReconciler reconciler(URI url) {
    return new AnalyticsReconciler(
        List.of(
            new AnalyticsReconciler.Pair(
                "appraisal", "appraisal_profile", "fact_appraisal_profile")),
        List.of(appraisal),
        new ClickHouseAggregates(HTTP, url, DB, "doisoat", "doisoat-thu"),
        new ReconcileMetrics(new SimpleMeterRegistry(), clock),
        new ObjectMapper(),
        clock);
  }

  private ReconcileRun run(TenantId t) {
    return inTenant(
        t,
        () ->
            new JdbcReconcileRunStore(appraisal.jdbc(), "appraisal")
                .find(AnalyticsReconciler.runId("appraisal", t.value(), clock.instant()))
                .orElseThrow());
  }

  private UUID profile(TenantId t) {
    UUID id = UUID.randomUUID();
    inTenant(
        t,
        () ->
            appraisal
                .jdbc()
                .update(
                    "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
                        + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
                        + " policy_pack_version_id) VALUES (?, gen_random_uuid(), gen_random_uuid(),"
                        + " gen_random_uuid(), ?, gen_random_uuid(), 'DANG', gen_random_uuid(),"
                        + " gen_random_uuid(), gen_random_uuid(), gen_random_uuid())",
                    id,
                    BUCKET));
    return id;
  }

  private static void fact(TenantId t, UUID id, int version, int deleted) throws Exception {
    ch(
        "INSERT INTO "
            + DB
            + ".fact_appraisal_profile VALUES ('"
            + t.value()
            + "', "
            + BUCKET
            + ", '"
            + id
            + "', "
            + version
            + ", "
            + deleted
            + ")");
  }

  private static void ch(String sql) throws Exception {
    HttpResponse<String> res =
        HTTP.send(
            HttpRequest.newBuilder(base)
                .header(
                    "Authorization",
                    "Basic "
                        + java.util.Base64.getEncoder()
                            .encodeToString("doisoat:doisoat-thu".getBytes()))
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
  }

  private static <T> T inTenant(TenantId t, java.util.concurrent.Callable<T> work) {
    try {
      return TenantContext.call(
          t,
          () ->
              new TransactionTemplate(appraisal.tm())
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
      this.now = now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofMinutes(35));
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
