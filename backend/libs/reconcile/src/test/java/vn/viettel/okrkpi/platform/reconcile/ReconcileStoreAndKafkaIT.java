package vn.viettel.okrkpi.platform.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** Kho {@code reconcile_run} trên PostgreSQL thật và bộ đếm Kafka theo dấu thời gian. */
class ReconcileStoreAndKafkaIT {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
  static JdbcTemplate jdbc;

  @BeforeAll
  static void start() {
    PG.start();
    KAFKA.start();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    jdbc.execute("CREATE SCHEMA demo");
    jdbc.execute(
        """
        CREATE TABLE demo.reconcile_run (
          id uuid PRIMARY KEY, tenant_id uuid NOT NULL, job varchar(32) NOT NULL,
          entity_type varchar(16) NOT NULL, started_at timestamptz NOT NULL DEFAULT now(),
          finished_at timestamptz, buckets_total int, buckets_mismatch int, rows_repaired int,
          status varchar(12) NOT NULL, detail jsonb NOT NULL DEFAULT '{}'::jsonb)""");
  }

  @AfterAll
  static void stop() {
    KAFKA.stop();
    PG.stop();
  }

  @Test
  void ghiKetThucVaLayLanChoXuLy() {
    var store = new JdbcReconcileRunStore(jdbc, "demo");
    UUID tenant = UUID.randomUUID();
    Instant t0 = Instant.parse("2026-10-22T01:00:00Z");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThat(store.insert(ReconcileRun.start(a, tenant, "R1", "TAT_CA", t0, 256, "{\"x\":1}")))
        .isTrue();
    assertThat(store.insert(ReconcileRun.start(a, tenant, "R1", "TAT_CA", t0, 256, null)))
        .isFalse();
    store.insert(ReconcileRun.start(b, tenant, "R1", "TAT_CA", t0.plusSeconds(60), 256, null));

    assertThat(store.pending("R1", t0.plusSeconds(30), 10))
        .extracting(ReconcileRun::id)
        .containsExactly(a);
    store.finish(a, RunStatus.LOI, 3, 0, "{\"buckets\":[1,2,3]}", t0.plusSeconds(90));

    ReconcileRun done = store.find(a).orElseThrow();
    assertThat(done.status()).isEqualTo(RunStatus.LOI);
    assertThat(done.bucketsMismatch()).isEqualTo(3);
    assertThat(done.detail()).contains("\"x\": 1").contains("\"buckets\": [1, 2, 3]");
    assertThat(store.lastFinished("R1", "TAT_CA", t0.plusSeconds(120)).orElseThrow().id())
        .isEqualTo(a);
    assertThat(store.pending("R1", t0.plusSeconds(120), 10))
        .extracting(ReconcileRun::id)
        .containsExactly(b);
    assertThatThrownBy(() -> new JdbcReconcileRunStore(jdbc, "demo; drop"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.finish(b, RunStatus.DANG_CHAY, 0, 0, null, t0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void demThongDiepTheoCuaSoThoiGian() throws Exception {
    Properties p = new Properties();
    p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (Admin admin = Admin.create(p)) {
      admin
          .createTopics(java.util.List.of(new NewTopic("demo.dem.thu.v1", 3, (short) 1)))
          .all()
          .get();
      Instant h = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
      Map<String, Object> cfg =
          Map.of(
              ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
              ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
              ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
      try (var producer = new KafkaProducer<String, String>(cfg)) {
        // 2 trước cửa sổ, 5 trong cửa sổ (rải 3 partition), 1 sau cửa sổ.
        long[] offsets = {-600, -1, 0, 60, 1200, 3000, 3599, 3600};
        int i = 0;
        for (long s : offsets) {
          producer.send(
              new ProducerRecord<>(
                  "demo.dem.thu.v1", i % 3, h.plusSeconds(s).toEpochMilli(), "k" + i, "v" + i));
          i++;
        }
        producer.flush();
      }
      var counter = new KafkaTopicCounter(admin, Duration.ofSeconds(10));
      assertThat(counter.count("demo.dem.thu.v1", h, h.plusSeconds(3600))).isEqualTo(5);
      assertThat(counter.count("demo.dem.thu.v1", h.minusSeconds(3600), h.plusSeconds(7200)))
          .isEqualTo(8);
      assertThat(counter.count("demo.khong.co.v1", h, h.plusSeconds(3600))).isZero();
      assertThat(counter.count("demo.dem.thu.v1", h, h)).isZero();
    }
  }

  @Test
  void chiSoCoNhanJobVaTrangThai() {
    var registry = new SimpleMeterRegistry();
    var metrics = new ReconcileMetrics(registry, Clock.systemUTC());
    metrics.finished("R7", RunStatus.LOI, 2, 0, Duration.ofMillis(5));
    metrics.finished("R7", RunStatus.KHOP, 0, 0, Duration.ofMillis(5));
    assertThat(registry.get(ReconcileMetrics.RUNS).tag("status", "LOI").counter().count())
        .isEqualTo(1);
    assertThat(registry.get(ReconcileMetrics.MISMATCHES).tag("reconcile", "R7").counter().count())
        .isEqualTo(2);
    assertThat(registry.get(ReconcileMetrics.LAST_FINISHED).gauge().value()).isPositive();
    assertThat(registry.get(ReconcileMetrics.DURATION).timer().count()).isEqualTo(2);
  }
}
