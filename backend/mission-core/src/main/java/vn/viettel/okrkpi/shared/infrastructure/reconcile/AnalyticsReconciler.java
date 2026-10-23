package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.ClickHouseAggregates.Agg;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.ClickHouseAggregates.Group;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.OutboxKafkaReconciler.OutboxSchema;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job R5 của DL-12 (TL-03 bảng đối soát, dòng "Kho phân tích"): đếm dòng và tổng kiểm mã theo
 * (tenant, {@code period_bucket}) ở bảng nguồn PostgreSQL và bảng sự kiện ClickHouse mà CDC phân
 * tích (DV-15-02) nạp vào, hằng giờ.
 *
 * <ul>
 *   <li>Theo kỳ thay vì (tenant, kỳ, đơn vị): bảng sự kiện không cùng có cột đơn vị (đơn vị của
 *       nhiệm vụ nằm ở bảng cầu), nên nhóm theo {@code period_bucket}; nhóm lệch ghi đủ để nạp lại
 *       phân vùng (suy luận).
 *   <li>CDC trễ vài phút làm lệch tạm thời, nên một nhóm chỉ là lệch khi lệch ở hai lần chạy liền
 *       nhau với cùng số liệu nguồn chưa đổi hoặc đã đổi mà vẫn lệch; lần đầu ghi {@code suspects}
 *       trong {@code detail} và trạng thái {@code KHOP} (suy luận).
 *   <li>Mỗi (schema, tenant) một dòng {@code reconcile_run} ở schema nguồn mỗi giờ; mã tất định
 *       theo (schema, tenant, giờ). Xử lý: {@code LOI} kèm danh sách phân vùng cần nạp lại ở {@code
 *       detail.repair} theo runbook {@code doi-soat-kho-phan-tich.md}.
 * </ul>
 */
public class AnalyticsReconciler {

  public static final String JOB = "R5_KHO_PHAN_TICH";
  public static final String ENTITY = "BANG_SU_KIEN";

  private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*");
  private static final Logger log = LoggerFactory.getLogger(AnalyticsReconciler.class);

  /** Một cặp bảng nguồn ({@code schema.table}) và bảng sự kiện ClickHouse. */
  public record Pair(String schema, String table, String fact) {
    public Pair {
      for (String n : List.of(schema, table, fact)) {
        if (n == null || !NAME.matcher(n).matches()) {
          throw new IllegalArgumentException("Tên không hợp lệ: " + n);
        }
      }
    }
  }

  /** Bảy cặp mặc định theo connector phân tích {@code analytics/cdc} (DV-15-01, DV-15-02). */
  public static final List<Pair> DEFAULT_PAIRS =
      List.of(
          new Pair("task", "task", "fact_task"),
          new Pair("task", "task_progress", "fact_task_progress"),
          new Pair("product", "evidence", "fact_evidence"),
          new Pair("okr", "kr_checkin", "fact_okr_checkin"),
          new Pair("okr", "objective", "fact_okr_objective"),
          new Pair("appraisal", "appraisal_profile", "fact_appraisal_profile"),
          new Pair("appraisal", "score_snapshot", "fact_score"));

  /** Tóm tắt một lần chạy của một (schema, tenant). */
  public record Outcome(String schema, TenantId tenant, RunStatus status, int mismatched) {}

  private final List<Pair> pairs;
  private final Map<String, OutboxSchema> schemas;
  private final ClickHouseAggregates clickhouse;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;

  public AnalyticsReconciler(
      List<Pair> pairs,
      List<OutboxSchema> schemas,
      ClickHouseAggregates clickhouse,
      ReconcileMetrics metrics,
      ObjectMapper json,
      Clock clock) {
    this.pairs = List.copyOf(pairs);
    this.schemas = new HashMap<>();
    schemas.forEach(s -> this.schemas.put(s.name(), s));
    this.clickhouse = Objects.requireNonNull(clickhouse, "clickhouse");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public static UUID runId(String schema, UUID tenant, Instant hour) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + schema + "|" + tenant + "|" + hour.truncatedTo(ChronoUnit.HOURS))
            .getBytes(StandardCharsets.UTF_8));
  }

  /** Đối soát mọi cặp cho danh sách tenant. */
  public List<Outcome> run(List<TenantId> tenants) {
    Instant started = clock.instant();
    Map<String, Map<Group, Agg>> facts = new LinkedHashMap<>();
    for (Pair p : pairs) {
      if (!schemas.containsKey(p.schema())) {
        continue;
      }
      try {
        facts.put(p.fact(), clickhouse.aggregate(p.fact()));
      } catch (RuntimeException e) {
        log.warn("Đối soát R5 không đọc được bảng {}", p.fact(), e);
      }
    }
    List<Outcome> out = new ArrayList<>();
    Set<String> used = new TreeSet<>();
    pairs.forEach(p -> used.add(p.schema()));
    for (String schema : used) {
      OutboxSchema s = schemas.get(schema);
      if (s == null) {
        continue;
      }
      List<Pair> mine = pairs.stream().filter(p -> p.schema().equals(schema)).toList();
      for (TenantId t : tenants) {
        try {
          Outcome o = inTenant(s, t, () -> runTenant(s, t, mine, facts, started));
          if (o != null) {
            out.add(o);
          }
        } catch (RuntimeException e) {
          log.warn("Đối soát R5 lỗi: schema={} tenant={}", schema, t.value(), e);
        }
      }
    }
    return out;
  }

  private Outcome runTenant(
      OutboxSchema s,
      TenantId tenant,
      List<Pair> mine,
      Map<String, Map<Group, Agg>> facts,
      Instant started) {
    JdbcReconcileRunStore runs = new JdbcReconcileRunStore(s.jdbc(), s.name());
    UUID id = runId(s.name(), tenant.value(), started);
    Set<String> previous = suspects(runs, started);
    ArrayNode groups = json.createArrayNode();
    List<String> now = new ArrayList<>();
    ArrayNode unread = json.createArrayNode();
    int compared = 0;
    for (Pair p : mine) {
      Map<Group, Agg> ch = facts.get(p.fact());
      if (ch == null) {
        unread.add(p.fact());
        continue;
      }
      Map<Integer, Agg> pg = source(s, p);
      Set<Integer> buckets = new TreeSet<>(pg.keySet());
      ch.keySet().stream()
          .filter(g -> g.tenant().equals(tenant.value()))
          .forEach(g -> buckets.add(g.periodBucket()));
      for (int b : buckets) {
        compared++;
        Agg a = pg.getOrDefault(b, new Agg(0, 0));
        Agg c = ch.getOrDefault(new Group(tenant.value(), b), new Agg(0, 0));
        if (!a.equals(c)) {
          String key = p.fact() + ":" + b;
          now.add(key);
          groups.add(
              json.createObjectNode()
                  .put("table", p.schema() + "." + p.table())
                  .put("fact", p.fact())
                  .put("periodBucket", b)
                  .put("pgRows", a.rows())
                  .put("chRows", c.rows())
                  .put("pgChecksum", a.checksum())
                  .put("chChecksum", c.checksum()));
        }
      }
    }
    if (compared == 0 && unread.isEmpty()) {
      return null;
    }
    List<String> confirmed = now.stream().filter(previous::contains).toList();
    RunStatus status = !confirmed.isEmpty() || !unread.isEmpty() ? RunStatus.LOI : RunStatus.KHOP;
    ObjectNode detail = json.createObjectNode().put("schema", s.name()).put("compared", compared);
    if (!now.isEmpty()) {
      detail.set("suspects", json.valueToTree(now));
      detail.set("groups", groups);
    }
    if (!confirmed.isEmpty()) {
      detail.set("confirmed", json.valueToTree(confirmed));
      detail.set("repair", repair(tenant, confirmed));
    }
    if (!unread.isEmpty()) {
      detail.put("reason", "KHONG_DOC_DUOC_CLICKHOUSE");
      detail.set("unread", unread);
    }
    if (!runs.insert(
        ReconcileRun.start(id, tenant.value(), JOB, ENTITY, started, compared, "{}"))) {
      return null;
    }
    Instant end = clock.instant();
    runs.finish(id, status, confirmed.size(), 0, write(detail), end);
    metrics.finished(JOB, status, confirmed.size(), 0, Duration.between(started, end));
    if (status == RunStatus.LOI) {
      log.warn(
          "Đối soát R5 lệch: schema={} tenant={} nhóm lệch={}",
          s.name(),
          tenant.value(),
          confirmed);
    }
    return new Outcome(s.name(), tenant, status, confirmed.size());
  }

  /** Nhóm nghi lệch của lần chạy kết thúc gần nhất trước giờ này. */
  private Set<String> suspects(JdbcReconcileRunStore runs, Instant before) {
    return runs.lastFinished(JOB, ENTITY, before.truncatedTo(ChronoUnit.HOURS))
        .map(
            r -> {
              Set<String> out = new HashSet<>();
              for (JsonNode n : read(r.detail()).path("suspects")) {
                out.add(n.asText());
              }
              return out;
            })
        .orElse(Set.of());
  }

  private Map<Integer, Agg> source(OutboxSchema s, Pair p) {
    Map<Integer, Agg> out = new HashMap<>();
    s.jdbc()
        .query(
            "SELECT period_bucket, count(*), coalesce(sum(('x' || substr(md5(lower(id::text)), 1,"
                + " 8))::bit(32)::bigint), 0) FROM "
                + p.schema()
                + "."
                + p.table()
                + " GROUP BY period_bucket",
            rs -> {
              out.put(rs.getInt(1), new Agg(rs.getLong(2), rs.getLong(3)));
            });
    return out;
  }

  /** Phân vùng cần nạp lại (bảng sự kiện, kỳ) cho người vận hành; cách nạp ở runbook. */
  private ArrayNode repair(TenantId tenant, List<String> confirmed) {
    ArrayNode a = json.createArrayNode();
    for (String c : confirmed) {
      String[] k = c.split(":");
      a.add(
          json.createObjectNode()
              .put("fact", k[0])
              .put("tenant", tenant.value().toString())
              .put("periodBucket", Integer.parseInt(k[1])));
    }
    return a;
  }

  private static <T> T inTenant(OutboxSchema s, TenantId tenant, Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              new TransactionTemplate(s.tm())
                  .execute(
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

  private JsonNode read(String s) {
    try {
      return json.readTree(s);
    } catch (JsonProcessingException e) {
      return json.createObjectNode();
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
