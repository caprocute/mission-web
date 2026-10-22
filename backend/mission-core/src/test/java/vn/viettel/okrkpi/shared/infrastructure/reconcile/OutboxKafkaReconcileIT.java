package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.platform.reconcile.TopicCounter;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.OutboxKafkaReconciler.OutboxSchema;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * R7 (DL-12) trên PostgreSQL thật, schema {@code task} và {@code okr} với vai trò {@code svc_}:
 * khớp thì {@code KHOP}; Kafka thiếu thì chép lại dòng outbox (giữ mã sự kiện) và {@code DA_SUA};
 * CDC đang dừng thì không phát lại mà {@code LOI}; chạy lại cùng cửa sổ không ghi trùng. Kafka thay
 * bằng bộ đếm giả theo topic (bộ đếm thật kiểm ở {@code vt-reconcile}).
 */
class OutboxKafkaReconcileIT {

  private static final Duration GRACE = Duration.ofMinutes(5);
  private static OutboxSchema task;
  private static OutboxSchema okr;
  private static final Map<String, Long> KAFKA = new ConcurrentHashMap<>();
  private static final Map<String, Long> KAFKA_TAIL = new ConcurrentHashMap<>();
  private static Instant now;
  private static Instant from;
  private static Instant to;
  private static SimpleMeterRegistry meters;
  private static OutboxKafkaReconciler reconciler;

  private TenantId a;
  private TenantId b;

  @BeforeAll
  static void setUp() {
    task = schema("task");
    okr = schema("okr");
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    from = now.minus(Duration.ofMinutes(40));
    to = now.minus(Duration.ofMinutes(20));
    TopicCounter kafka =
        (topic, f, t) ->
            f.equals(from)
                ? KAFKA.getOrDefault(topic, 0L)
                : f.equals(to) ? KAFKA_TAIL.getOrDefault(topic, 1L) : 0L;
    meters = new SimpleMeterRegistry();
    reconciler =
        new OutboxKafkaReconciler(
            List.of(task, okr),
            kafka,
            new ReconcileMetrics(meters, Clock.systemUTC()),
            new ObjectMapper(),
            Clock.fixed(now, java.time.ZoneOffset.UTC),
            GRACE,
            true,
            1_000);
  }

  @BeforeEach
  void tenants() {
    a = TenantId.of(UUID.randomUUID());
    b = TenantId.of(UUID.randomUUID());
  }

  @Test
  void haiPhiaBangNhauThiKhop() {
    insert(task, a, "task.doisoat.khop.v1", from.plusSeconds(10), 3);
    insert(task, a, "task.doisoat.khop.v1", from.minusSeconds(10), 1);
    KAFKA.put("task.doisoat.khop.v1", 3L);

    var out = reconciler.run(from, to, List.of(a));
    assertThat(out).extracting(OutboxKafkaReconciler.Outcome::lagging).allMatch(List::isEmpty);
    ReconcileRun run = run(task, a);
    assertThat(run.status()).isEqualTo(RunStatus.KHOP);
    assertThat(run.bucketsTotal()).isEqualTo(1);
    assertThat(run.detail()).contains("\"outbox\": 3").contains("\"kafkaCell\": 3");
  }

  @Test
  void kafkaThieuThiPhatLaiTuOutboxGiuMaSuKien() {
    insert(okr, a, "okr.doisoat.lech.v1", from.plusSeconds(60), 2);
    insert(okr, b, "okr.doisoat.lech.v1", from.plusSeconds(120), 1);
    insert(okr, b, "okr.doisoat.du.v1", from.plusSeconds(120), 1);
    KAFKA.put("okr.doisoat.lech.v1", 1L);
    KAFKA.put("okr.doisoat.du.v1", 1L);

    var out = reconciler.run(from, to, List.of(a, b));
    var okrOut = out.stream().filter(o -> o.schema().equals("okr")).findFirst().orElseThrow();
    assertThat(okrOut.lagging()).containsExactly("okr.doisoat.lech.v1");
    assertThat(okrOut.replayed()).isEqualTo(3);

    ReconcileRun ra = run(okr, a);
    assertThat(ra.status()).isEqualTo(RunStatus.DA_SUA);
    assertThat(ra.rowsRepaired()).isEqualTo(2);
    assertThat(ra.bucketsMismatch()).isEqualTo(1);
    ReconcileRun rb = run(okr, b);
    assertThat(rb.status()).isEqualTo(RunStatus.DA_SUA);
    assertThat(rb.rowsRepaired()).isEqualTo(1);
    assertThat(rb.bucketsTotal()).isEqualTo(2);
    // Bản chép giữ mã sự kiện, ghi ở thời điểm mới.
    assertThat(count(okr, a, "count(*)")).isEqualTo(4);
    assertThat(count(okr, a, "count(DISTINCT id)")).isEqualTo(2);

    // Chạy lại cùng cửa sổ (bản sao thứ hai của job): không ghi trùng, không phát lại lần nữa.
    var again = reconciler.run(from, to, List.of(a, b));
    assertThat(again).extracting(OutboxKafkaReconciler.Outcome::replayed).containsOnly(0);
    assertThat(count(okr, a, "count(*)")).isEqualTo(4);
    assertThat(
            meters
                .find(ReconcileMetrics.REPAIRED)
                .tag("reconcile", OutboxKafkaReconciler.JOB)
                .counter()
                .count())
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  void cdcDangDungThiKhongPhatLaiMaBaoLoi() {
    insert(task, a, "task.doisoat.dung.v1", from.plusSeconds(30), 2);
    insert(task, a, "task.doisoat.dung.v1", to.plusSeconds(60), 1);
    KAFKA.put("task.doisoat.dung.v1", 0L);
    KAFKA_TAIL.put("task.doisoat.dung.v1", 0L);

    reconciler.run(from, to, List.of(a));
    ReconcileRun run = run(task, a);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.rowsRepaired()).isZero();
    assertThat(run.detail())
        .contains("\"reason\": \"CDC_DUNG\"")
        .contains("outbox-replay --schema=task --tenant=" + a.value());
    assertThat(count(task, a, "count(*)")).isEqualTo(3);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private static OutboxSchema schema(String name) {
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(3);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=" + name);
    ds.setUsername("svc_" + name);
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    return new OutboxSchema(
        name,
        new JdbcTemplate(ds),
        new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null)));
  }

  private static void insert(OutboxSchema s, TenantId t, String topic, Instant at, int n) {
    inTenant(
        s,
        t,
        () -> {
          for (int i = 0; i < n; i++) {
            UUID agg = UUID.randomUUID();
            s.jdbc()
                .update(
                    "INSERT INTO "
                        + s.name()
                        + ".outbox_event (id, tenant_id, aggregate_type, aggregate_id,"
                        + " aggregate_version, event_type, topic, event_key, payload, schema_version,"
                        + " created_at) VALUES (?, ?, 'Thu', ?, 1, 'thu', ?, ?, ?, 1, ?)",
                    UUID.randomUUID(),
                    t.value(),
                    agg,
                    topic,
                    t.value() + ":" + agg,
                    new byte[] {1},
                    Timestamp.from(at));
          }
          return null;
        });
  }

  private static ReconcileRun run(OutboxSchema s, TenantId t) {
    return inTenant(
        s,
        t,
        () ->
            new JdbcReconcileRunStore(s.jdbc(), s.name())
                .find(OutboxKafkaReconciler.runId(s.name(), t.value(), from))
                .orElseThrow());
  }

  private static long count(OutboxSchema s, TenantId t, String expr) {
    return inTenant(
        s,
        t,
        () ->
            s.jdbc()
                .queryForObject(
                    "SELECT " + expr + " FROM " + s.name() + ".outbox_event", Long.class));
  }

  private static <T> T inTenant(OutboxSchema s, TenantId t, java.util.concurrent.Callable<T> work) {
    try {
      return TenantContext.call(
          t,
          () ->
              new TransactionTemplate(s.tm())
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
}
