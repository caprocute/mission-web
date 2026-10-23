package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Bộ lấy mẫu các chỉ số giám sát engine Flowable cần đếm trong CSDL (TL-01 G2, DV-06-01, DV-06-11):
 * job đến hạn chưa khoá, job thư chết, số dòng {@code ACT_RU_EXECUTION} và {@code ACT_HI_*}, tuổi
 * p95 của job external worker đang chờ, số định nghĩa tiến trình đang hoạt động. Mỗi 15 giây chạy
 * một lượt trên bản sao đọc (khi có), truy vấn đếm dừng ở {@value #COUNT_LIMIT} dòng, bảng lớn lấy
 * ước lượng {@code pg_class.reltuples}; lượt scrape chỉ đọc giá trị đã lấy. Truy vấn lỗi giữ giá
 * trị cũ và tăng {@code flowable_metrics_sample_failures_total}.
 */
public class FlowableEngineMetrics {

  public static final String DUE_UNLOCKED = "flowable_async_jobs_due_unlocked";
  public static final String DEADLETTER = "flowable_deadletter_jobs";
  public static final String RU_EXECUTION_ROWS = "flowable_ru_execution_rows";
  public static final String HI_ROWS = "flowable_history_rows";
  public static final String EXTERNAL_AGE_P95 = "flowable_external_worker_job_age_p95_seconds";
  public static final String ACTIVE_DEFINITIONS = "flowable_active_process_definitions";
  public static final String SAMPLE_FAILURES = "flowable.metrics.sample.failures";

  static final int COUNT_LIMIT = 100_000;

  /** Bảng lịch sử theo dõi tăng trưởng; Debezium chép ba bảng đầu sang ClickHouse. */
  static final List<String> HISTORY_TABLES =
      List.of(
          "act_hi_procinst",
          "act_hi_actinst",
          "act_hi_taskinst",
          "act_hi_varinst",
          "act_hi_identitylink");

  static final String DUE_UNLOCKED_SQL =
      "SELECT count(*) FROM (SELECT 1 FROM flowable.act_ru_job WHERE lock_owner_ IS NULL"
          + " AND (duedate_ IS NULL OR duedate_ <= now()) LIMIT "
          + (COUNT_LIMIT + 1)
          + ") j";
  static final String DEADLETTER_SQL =
      "SELECT count(*) FROM (SELECT 1 FROM flowable.act_ru_deadletter_job LIMIT "
          + (COUNT_LIMIT + 1)
          + ") d";
  static final String RELTUPLES_SQL =
      "SELECT c.relname, greatest(c.reltuples, 0)::bigint FROM pg_class c"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = 'flowable' AND c.relname IN ('act_ru_execution', "
          + String.join(", ", HISTORY_TABLES.stream().map(t -> "'" + t + "'").toList())
          + ")";
  static final String EXTERNAL_AGE_SQL =
      "SELECT coalesce(extract(epoch FROM percentile_cont(0.95) WITHIN GROUP"
          + " (ORDER BY now() - create_time_)), 0) FROM (SELECT create_time_ FROM"
          + " flowable.act_ru_external_job WHERE create_time_ IS NOT NULL LIMIT "
          + COUNT_LIMIT
          + ") e";
  static final String ACTIVE_DEFINITIONS_SQL =
      "SELECT count(*) FROM (SELECT 1 FROM flowable.act_re_procdef d WHERE d.suspension_state_ = 1"
          + " AND d.version_ = (SELECT max(x.version_) FROM flowable.act_re_procdef x"
          + " WHERE x.key_ = d.key_ AND x.tenant_id_ = d.tenant_id_) LIMIT "
          + (COUNT_LIMIT + 1)
          + ") p";

  private static final Logger LOG = LoggerFactory.getLogger(FlowableEngineMetrics.class);

  private final JdbcTemplate jdbc;
  private final Counter failures;
  private final AtomicReference<Double> dueUnlocked = new AtomicReference<>(0.0);
  private final AtomicReference<Double> deadletterJobs = new AtomicReference<>(0.0);
  private final AtomicReference<Double> externalAge = new AtomicReference<>(0.0);
  private final AtomicReference<Double> activeDefinitions = new AtomicReference<>(0.0);
  private final Map<String, AtomicReference<Double>> rows = new LinkedHashMap<>();

  public FlowableEngineMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    failures =
        Counter.builder(SAMPLE_FAILURES)
            .description("Số lần truy vấn lấy mẫu chỉ số Flowable lỗi")
            .register(registry);
    gauge(registry, DUE_UNLOCKED, dueUnlocked, "Số job bất đồng bộ đến hạn chưa khoá");
    gauge(registry, DEADLETTER, deadletterJobs, "Số job trong ACT_RU_DEADLETTER_JOB");
    gauge(registry, EXTERNAL_AGE_P95, externalAge, "Tuổi p95 của job external worker đang chờ");
    gauge(
        registry,
        ACTIVE_DEFINITIONS,
        activeDefinitions,
        "Số định nghĩa tiến trình bản mới nhất đang hoạt động");
    AtomicReference<Double> execution = new AtomicReference<>(0.0);
    rows.put("act_ru_execution", execution);
    gauge(registry, RU_EXECUTION_ROWS, execution, "Số dòng ACT_RU_EXECUTION (ước lượng)");
    for (String table : HISTORY_TABLES) {
      AtomicReference<Double> value = new AtomicReference<>(0.0);
      rows.put(table, value);
      Gauge.builder(HI_ROWS, value, AtomicReference::get)
          .description("Số dòng bảng lịch sử Flowable (ước lượng)")
          .tag("table", table)
          .register(registry);
    }
  }

  private static void gauge(
      MeterRegistry registry, String name, AtomicReference<Double> value, String description) {
    Gauge.builder(name, value, AtomicReference::get).description(description).register(registry);
  }

  /** Một lượt lấy mẫu; lịch chạy mặc định 15 giây. */
  @Scheduled(
      fixedDelayString = "${okrkpi.workflow.metrics.sample-interval:15s}",
      initialDelayString = "${okrkpi.workflow.metrics.initial-delay:0s}")
  public void sample() {
    count(DUE_UNLOCKED_SQL, dueUnlocked);
    count(DEADLETTER_SQL, deadletterJobs);
    count(EXTERNAL_AGE_SQL, externalAge);
    count(ACTIVE_DEFINITIONS_SQL, activeDefinitions);
    try {
      jdbc.query(
          RELTUPLES_SQL,
          rs -> {
            AtomicReference<Double> value = rows.get(rs.getString(1));
            if (value != null) {
              value.set((double) rs.getLong(2));
            }
          });
    } catch (DataAccessException e) {
      failed(e);
    }
  }

  private void count(String sql, AtomicReference<Double> target) {
    try {
      Number value = jdbc.queryForObject(sql, Number.class);
      target.set(value == null ? 0.0 : value.doubleValue());
    } catch (DataAccessException e) {
      failed(e);
    }
  }

  private void failed(DataAccessException e) {
    failures.increment();
    LOG.warn("Không lấy được mẫu chỉ số Flowable: {}", e.getMostSpecificCause().getMessage());
  }

  /** Giá trị vừa lấy của job đến hạn chưa khoá (cho kiểm thử và KEDA). */
  public double dueUnlockedJobs() {
    return dueUnlocked.get();
  }
}
