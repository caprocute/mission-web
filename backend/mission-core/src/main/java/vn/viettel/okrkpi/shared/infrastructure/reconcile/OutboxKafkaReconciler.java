package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.platform.reconcile.TopicCounter;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job R7 của DL-12 (TL-03 bảng đối soát, dòng Outbox): đếm sự kiện theo giờ ở {@code
 * <schema>.outbox_event} và ở Kafka, lệch thì phát lại từ outbox.
 *
 * <ul>
 *   <li>Phía outbox đếm theo (tenant, topic) trong cửa sổ từ {@code from} đến trước {@code to} theo
 *       {@code created_at}, lần lượt từng tenant vì bảng có RLS. Phía Kafka đếm theo topic trên
 *       toàn cell bằng dấu thời gian thông điệp, cửa sổ nới thêm {@code grace} vì Debezium ghi
 *       thông điệp sau khi giao dịch commit (suy luận). Kafka không tách được theo tenant nếu không
 *       đọc thông điệp, nên chỉ coi là lệch khi tổng Kafka của topic nhỏ hơn tổng outbox; Kafka lớn
 *       hơn (phát lại, đếm chồng cửa sổ) không phải lệch.
 *   <li>Mỗi tenant có sự kiện trong cửa sổ được một dòng {@code reconcile_run} ở schema nguồn, mã
 *       tất định theo (schema, tenant, cửa sổ) nên hai bản sao của job không ghi trùng.
 *   <li>Xử lý: chép lại các dòng outbox của topic lệch trong cửa sổ với {@code created_at} mới, giữ
 *       nguyên {@code id}; Debezium phát lại cùng mã sự kiện và inbox phía nhận bỏ bản đã xử lý.
 *       Không phát lại khi CDC đang dừng (Kafka không có thông điệp nào của topic sau cửa sổ trong
 *       khi outbox có), vì khi chạy lại CDC tự đẩy phần tồn; khi đó ghi {@code LOI} và lệnh {@code
 *       outbox-replay} cho người vận hành (runbook {@code doi-soat-outbox-kafka.md}).
 * </ul>
 */
public class OutboxKafkaReconciler {

  public static final String JOB = "R7_OUTBOX_KAFKA";
  public static final String ENTITY = "OUTBOX";

  private static final Logger log = LoggerFactory.getLogger(OutboxKafkaReconciler.class);

  /**
   * Một schema có {@code outbox_event} cùng JdbcTemplate và bộ quản lý giao dịch đặt ngữ cảnh RLS.
   */
  public record OutboxSchema(String name, JdbcTemplate jdbc, PlatformTransactionManager tm) {
    public OutboxSchema {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(jdbc, "jdbc");
      Objects.requireNonNull(tm, "tm");
    }
  }

  /** Tóm tắt một lần đối soát của một schema. */
  public record Outcome(String schema, int tenants, List<String> lagging, int replayed) {}

  private final List<OutboxSchema> schemas;
  private final TopicCounter kafka;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;
  private final Duration grace;
  private final boolean autoReplay;
  private final int replayLimit;

  public OutboxKafkaReconciler(
      List<OutboxSchema> schemas,
      TopicCounter kafka,
      ReconcileMetrics metrics,
      ObjectMapper json,
      Clock clock,
      Duration grace,
      boolean autoReplay,
      int replayLimit) {
    this.schemas = List.copyOf(schemas);
    this.kafka = Objects.requireNonNull(kafka, "kafka");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.grace = Objects.requireNonNull(grace, "grace");
    this.autoReplay = autoReplay;
    this.replayLimit = replayLimit;
  }

  public static UUID runId(String schema, UUID tenant, Instant from) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + schema + "|" + tenant + "|" + from).getBytes(StandardCharsets.UTF_8));
  }

  /** Đối soát cửa sổ từ {@code from} đến trước {@code to} cho mọi schema và tenant cho trước. */
  public List<Outcome> run(Instant from, Instant to, List<TenantId> tenants) {
    List<Outcome> out = new ArrayList<>();
    for (OutboxSchema s : schemas) {
      try {
        out.add(runSchema(s, from, to, tenants));
      } catch (RuntimeException e) {
        log.warn("Đối soát R7 lỗi ở schema {}", s.name(), e);
      }
    }
    return out;
  }

  private Outcome runSchema(OutboxSchema s, Instant from, Instant to, List<TenantId> tenants) {
    Instant started = clock.instant();
    TransactionTemplate tx = new TransactionTemplate(s.tm());
    Map<TenantId, Map<String, Long>> perTenant = new LinkedHashMap<>();
    Map<String, Long> totals = new TreeMap<>();
    for (TenantId t : tenants) {
      Map<String, Long> counts = inTenant(t, tx, () -> countOutbox(s, from, to));
      if (!counts.isEmpty()) {
        perTenant.put(t, counts);
        counts.forEach((topic, n) -> totals.merge(topic, n, Long::sum));
      }
    }
    if (perTenant.isEmpty()) {
      // Không có sự kiện trong cửa sổ: vẫn ghi chỉ số để cảnh báo "ngừng chạy" không bật nhầm.
      metrics.finished(JOB, RunStatus.KHOP, 0, 0, Duration.between(started, clock.instant()));
      return new Outcome(s.name(), 0, List.of(), 0);
    }
    Map<String, Long> kafkaCounts = new TreeMap<>();
    TreeSet<String> lagging = new TreeSet<>();
    TreeSet<String> stalled = new TreeSet<>();
    Instant now = clock.instant();
    for (Map.Entry<String, Long> e : totals.entrySet()) {
      long k = kafka.count(e.getKey(), from, to.plus(grace));
      kafkaCounts.put(e.getKey(), k);
      if (k < e.getValue()) {
        lagging.add(e.getKey());
        Instant tail = now.minus(grace);
        if (tail.isAfter(to)
            && kafka.count(e.getKey(), to, tail) == 0
            && anyOutboxAfter(s, perTenant.keySet(), tx, e.getKey(), to, tail)) {
          stalled.add(e.getKey());
        }
      }
    }
    int replayed = 0;
    for (Map.Entry<TenantId, Map<String, Long>> e : perTenant.entrySet()) {
      replayed +=
          inTenant(
              e.getKey(),
              tx,
              () ->
                  finishTenant(
                      s,
                      e.getKey(),
                      e.getValue(),
                      kafkaCounts,
                      lagging,
                      stalled,
                      from,
                      to,
                      started));
    }
    if (!lagging.isEmpty()) {
      log.warn(
          "Đối soát R7 lệch: schema={} cửa sổ={}..{} topic={} đã phát lại={}",
          s.name(),
          from,
          to,
          lagging,
          replayed);
    }
    return new Outcome(s.name(), perTenant.size(), List.copyOf(lagging), replayed);
  }

  private int finishTenant(
      OutboxSchema s,
      TenantId tenant,
      Map<String, Long> counts,
      Map<String, Long> kafkaCounts,
      TreeSet<String> lagging,
      TreeSet<String> stalled,
      Instant from,
      Instant to,
      Instant started) {
    JdbcReconcileRunStore runs = new JdbcReconcileRunStore(s.jdbc(), s.name());
    UUID id = runId(s.name(), tenant.value(), from);
    ObjectNode window =
        json.createObjectNode().put("from", from.toString()).put("to", to.toString());
    ObjectNode topics = json.createObjectNode();
    counts.forEach(
        (t, n) ->
            topics.set(
                t, json.createObjectNode().put("outbox", n).put("kafkaCell", kafkaCounts.get(t))));
    ObjectNode start = json.createObjectNode();
    start.put("schema", s.name());
    start.set("window", window);
    start.set("topics", topics);
    if (!runs.insert(
        ReconcileRun.start(
            id, tenant.value(), JOB, ENTITY, started, counts.size(), write(start)))) {
      return 0;
    }
    List<String> mine = counts.keySet().stream().filter(lagging::contains).sorted().toList();
    RunStatus status = RunStatus.KHOP;
    int repaired = 0;
    ObjectNode end = json.createObjectNode();
    end.set("lagging", json.valueToTree(mine));
    if (!mine.isEmpty()) {
      List<String> replayable = mine.stream().filter(t -> !stalled.contains(t)).toList();
      long pending = replayable.isEmpty() ? 0 : countRows(s, replayable, from, to);
      if (autoReplay
          && !replayable.isEmpty()
          && pending <= replayLimit
          && replayable.size() == mine.size()) {
        repaired = replay(s, replayable, from, to);
        status = RunStatus.DA_SUA;
      } else {
        status = RunStatus.LOI;
        String reason;
        if (replayable.size() != mine.size()) {
          reason = "CDC_DUNG";
        } else {
          reason = autoReplay ? "VUOT_GIOI_HAN" : "TAT_TU_DONG";
        }
        end.put("reason", reason);
      }
      end.put(
          "replay",
          "outbox-replay --schema="
              + s.name()
              + " --tenant="
              + tenant.value()
              + " --from="
              + from
              + " --to="
              + to
              + " --topic="
              + String.join(",", mine));
    }
    Instant now = clock.instant();
    runs.finish(id, status, mine.size(), repaired, write(end), now);
    metrics.finished(JOB, status, mine.size(), repaired, Duration.between(started, now));
    return repaired;
  }

  private Map<String, Long> countOutbox(OutboxSchema s, Instant from, Instant to) {
    Map<String, Long> out = new TreeMap<>();
    s.jdbc()
        .query(
            "SELECT topic, count(*) FROM "
                + s.name()
                + ".outbox_event WHERE created_at >= ? AND created_at < ? GROUP BY topic",
            rs -> {
              out.put(rs.getString(1), rs.getLong(2));
            },
            Timestamp.from(from),
            Timestamp.from(to));
    return out;
  }

  private boolean anyOutboxAfter(
      OutboxSchema s,
      java.util.Set<TenantId> tenants,
      TransactionTemplate tx,
      String topic,
      Instant from,
      Instant to) {
    for (TenantId t : tenants) {
      Boolean any =
          inTenant(
              t,
              tx,
              () ->
                  s.jdbc()
                      .queryForObject(
                          "SELECT EXISTS (SELECT 1 FROM "
                              + s.name()
                              + ".outbox_event WHERE topic = ? AND created_at >= ? AND created_at < ?)",
                          Boolean.class,
                          topic,
                          Timestamp.from(from),
                          Timestamp.from(to)));
      if (Boolean.TRUE.equals(any)) {
        return true;
      }
    }
    return false;
  }

  private long countRows(OutboxSchema s, List<String> topics, Instant from, Instant to) {
    Long n =
        s.jdbc()
            .queryForObject(
                "SELECT count(*) FROM "
                    + s.name()
                    + ".outbox_event WHERE topic = ANY (?) AND created_at >= ? AND created_at < ?",
                Long.class,
                topics.toArray(String[]::new),
                Timestamp.from(from),
                Timestamp.from(to));
    return n == null ? 0 : n;
  }

  /** Chép lại dòng outbox (giữ {@code id}, {@code created_at} mới) để Debezium phát lại. */
  private int replay(OutboxSchema s, List<String> topics, Instant from, Instant to) {
    return s.jdbc()
        .update(
            "INSERT INTO "
                + s.name()
                + ".outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version,"
                + " event_type, topic, event_key, payload, schema_version, headers, created_at)"
                + " SELECT id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type,"
                + " topic, event_key, payload, schema_version, headers, clock_timestamp()"
                + " FROM "
                + s.name()
                + ".outbox_event WHERE topic = ANY (?) AND created_at >= ? AND created_at < ?",
            topics.toArray(String[]::new),
            Timestamp.from(from),
            Timestamp.from(to));
  }

  private static <T> T inTenant(TenantId tenant, TransactionTemplate tx, Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              tx.execute(
                  st -> {
                    try {
                      return work.call();
                    } catch (RuntimeException e) {
                      throw e;
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

  private String write(ObjectNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
