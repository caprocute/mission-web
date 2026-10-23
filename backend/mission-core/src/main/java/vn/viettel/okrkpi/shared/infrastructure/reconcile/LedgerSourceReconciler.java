package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerEntryLookup.LedgerRef;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSource.SourceEvent;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job R2 của DL-12 (TL-03 bảng đối soát, dòng "Sổ cái và dữ liệu nghiệp vụ"): hằng đêm lấy mẫu đối
 * tượng có sự kiện pháp lý trong ngày, so sổ cái với dữ liệu nguồn. Sổ cái chỉ giữ băm, còn tải
 * trọng mang giá trị chốt lúc phát ({@code aggregateVersion}…), nên không tính lại băm từ dòng hiện
 * hành mà làm hai bước (hướng B, wiki trang 14 mục DL-12e):
 *
 * <ol>
 *   <li>tính lại {@code payload_hash} từ thân Avro ở {@code outbox_event} và so với bản ghi cùng mã
 *       sự kiện trong sổ cái; thiếu bản ghi quá {@code ledgerGrace} hoặc lệch băm đều là lệch;
 *   <li>module nguồn so tải trọng đã khớp băm với dòng hiện hành ({@link LedgerSource#verify}):
 *       dòng nguồn bị sửa ngoài luồng nghiệp vụ thì trường không đổi sẽ lệch.
 * </ol>
 *
 * <p>Mẫu chọn tất định theo (ngày, đối tượng) với tỷ lệ {@code sampleRate}, ít nhất một đối tượng
 * mỗi tenant có sự kiện, tối đa {@code maxPerTenant}. Outbox giữ 3 ngày (pg_partman), nên job phải
 * chạy trong vòng 2 ngày sau cửa sổ. Mỗi (schema, tenant, ngày) một dòng {@code reconcile_run};
 * lệch thì {@code LOI} và chỉ số {@code
 * mission_reconcile_mismatches_total{reconcile="R2_SO_CAI_NGUON"}} để cảnh báo SOC mức cao. Không
 * tự sửa: sổ cái là nguồn đối chứng, người vận hành điều tra theo runbook.
 */
public class LedgerSourceReconciler {

  public static final String JOB = "R2_SO_CAI_NGUON";
  public static final String ENTITY = "SO_CAI";

  /** Loại lệch ghi trong {@code detail.mismatches[].kind}. */
  static final String THIEU_SO_CAI = "THIEU_SO_CAI";

  static final String LECH_BAM = "LECH_BAM_PAYLOAD";
  static final String LECH_NGUON = "LECH_DU_LIEU_NGUON";
  static final String THAN_HONG = "THAN_AVRO_HONG";

  private static final Logger log = LoggerFactory.getLogger(LedgerSourceReconciler.class);

  /** Tóm tắt một lần đối soát của một schema và tenant. */
  public record Outcome(
      String schema, TenantId tenant, RunStatus status, int sampled, int mismatched) {}

  private record Sampled(UUID aggregateId, List<SourceEvent> events, Map<UUID, byte[]> hashes) {}

  private final List<LedgerSource> sources;
  private final LedgerEntryLookup ledger;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;
  private final double sampleRate;
  private final int maxPerTenant;
  private final Duration ledgerGrace;

  public LedgerSourceReconciler(
      List<LedgerSource> sources,
      LedgerEntryLookup ledger,
      ReconcileMetrics metrics,
      ObjectMapper json,
      Clock clock,
      double sampleRate,
      int maxPerTenant,
      Duration ledgerGrace) {
    this.sources = List.copyOf(sources);
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (!(sampleRate > 0 && sampleRate <= 1)) {
      throw new IllegalArgumentException("Tỷ lệ lấy mẫu R2 phải trong (0, 1]");
    }
    this.sampleRate = sampleRate;
    this.maxPerTenant = Math.max(1, maxPerTenant);
    this.ledgerGrace = Objects.requireNonNull(ledgerGrace, "ledgerGrace");
  }

  public static UUID runId(String schema, UUID tenant, LocalDate day) {
    return UUID.nameUUIDFromBytes(
        (JOB + "|" + schema + "|" + tenant + "|" + day).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Đối soát ngày {@code day} (cửa sổ từ {@code from} đến trước {@code to} theo {@code created_at})
   * cho mọi nguồn, tenant.
   */
  public List<Outcome> run(LocalDate day, Instant from, Instant to, List<TenantId> tenants) {
    List<Outcome> out = new ArrayList<>();
    for (LedgerSource s : sources) {
      for (TenantId t : tenants) {
        try {
          Outcome o = runTenant(s, t, day, from, to);
          if (o != null) {
            out.add(o);
          }
        } catch (RuntimeException e) {
          log.warn("Đối soát R2 lỗi ở schema {} tenant {}", s.schema(), t.value(), e);
        }
      }
    }
    return out;
  }

  private Outcome runTenant(
      LedgerSource s, TenantId tenant, LocalDate day, Instant from, Instant to) {
    Instant started = clock.instant();
    TransactionTemplate tx = new TransactionTemplate(s.tm());
    UUID id = runId(s.schema(), tenant.value(), day);
    JdbcReconcileRunStore runs = new JdbcReconcileRunStore(s.jdbc(), s.schema());
    List<UUID> changed = inTenant(tenant, tx, () -> changedAggregates(s, from, to));
    if (changed.isEmpty()) {
      return null;
    }
    List<UUID> sample = sample(day, changed);
    ObjectNode start = json.createObjectNode();
    start.put("schema", s.schema());
    start.put("day", day.toString());
    start.set(
        "window", json.createObjectNode().put("from", from.toString()).put("to", to.toString()));
    start.put("changed", changed.size());
    start.put("sampleRate", sampleRate);
    start.set("topics", json.valueToTree(s.topics().keySet().stream().sorted().toList()));
    boolean fresh =
        inTenant(
            tenant,
            tx,
            () ->
                runs.insert(
                    ReconcileRun.start(
                        id, tenant.value(), JOB, ENTITY, started, sample.size(), write(start))));
    if (!fresh) {
      return null;
    }
    ArrayNode mismatches = json.createArrayNode();
    List<Sampled> loaded = new ArrayList<>();
    for (UUID agg : sample) {
      loaded.add(inTenant(tenant, tx, () -> load(s, agg, from, to, mismatches)));
    }
    String failure = null;
    Instant now = clock.instant();
    for (Sampled x : loaded) {
      if (x.events().isEmpty() && x.hashes().isEmpty()) {
        continue;
      }
      try {
        compareLedger(tenant, x, from, now, mismatches);
      } catch (DependencyUnavailableException e) {
        failure = "SO_CAI_KHONG_PHAN_HOI";
        log.warn("Đối soát R2 không đọc được sổ cái cho tenant {}", tenant.value(), e);
        break;
      }
    }
    List<Sampled> verified = loaded.stream().filter(x -> !x.events().isEmpty()).toList();
    inTenant(
        tenant,
        tx,
        () -> {
          for (Sampled x : verified) {
            for (String field : s.verify(x.aggregateId(), x.events())) {
              mismatches.add(
                  json.createObjectNode()
                      .put("aggregateId", x.aggregateId().toString())
                      .put("kind", LECH_NGUON)
                      .put("field", field));
            }
          }
          return null;
        });
    int mismatched = distinctAggregates(mismatches);
    RunStatus status = mismatched == 0 && failure == null ? RunStatus.KHOP : RunStatus.LOI;
    ObjectNode end = json.createObjectNode();
    end.put("sampled", sample.size());
    end.set("mismatches", mismatches);
    if (failure != null) {
      end.put("reason", failure);
    }
    Instant finished = clock.instant();
    inTenant(
        tenant,
        tx,
        () -> {
          runs.finish(id, status, mismatched, 0, write(end), finished);
          return null;
        });
    metrics.finished(JOB, status, mismatched, 0, Duration.between(started, finished));
    if (mismatched > 0) {
      log.error(
          "Đối soát R2 lệch sổ cái ↔ nguồn: schema={} tenant={} ngày={} đối tượng lệch={} (xem {}.reconcile_run {})",
          s.schema(),
          tenant.value(),
          day,
          mismatched,
          s.schema(),
          id);
    }
    return new Outcome(s.schema(), tenant, status, sample.size(), mismatched);
  }

  private List<UUID> changedAggregates(LedgerSource s, Instant from, Instant to) {
    return s.jdbc()
        .queryForList(
            "SELECT DISTINCT aggregate_id FROM "
                + s.schema()
                + ".outbox_event WHERE topic = ANY (?) AND created_at >= ? AND created_at < ?",
            UUID.class,
            s.topics().keySet().toArray(String[]::new),
            Timestamp.from(from),
            Timestamp.from(to));
  }

  /**
   * Chọn tất định theo băm (ngày, đối tượng): điểm nhỏ hơn tỷ lệ thì vào mẫu; ít nhất một, tối đa
   * trần.
   */
  List<UUID> sample(LocalDate day, List<UUID> changed) {
    Map<UUID, Double> score = new HashMap<>();
    for (UUID a : changed) {
      score.put(a, score(day, a));
    }
    List<UUID> sorted = changed.stream().sorted(Comparator.comparingDouble(score::get)).toList();
    List<UUID> picked = new ArrayList<>();
    for (UUID a : sorted) {
      if (picked.size() >= maxPerTenant || (!picked.isEmpty() && score.get(a) >= sampleRate)) {
        break;
      }
      picked.add(a);
    }
    return picked;
  }

  private static double score(LocalDate day, UUID aggregate) {
    try {
      byte[] h =
          MessageDigest.getInstance("SHA-256")
              .digest((day + "|" + aggregate).getBytes(StandardCharsets.UTF_8));
      long v = ByteBuffer.wrap(h, 0, 8).getLong() >>> 11;
      return v / (double) (1L << 53);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }

  /**
   * Sự kiện của đối tượng trong cửa sổ; dòng phát lại của R7 trùng {@code id} nên chỉ lấy dòng sớm
   * nhất. Thân hỏng ghi lệch {@link #THAN_HONG} và không đưa vào bước so nguồn.
   */
  private Sampled load(LedgerSource s, UUID agg, Instant from, Instant to, ArrayNode mismatches) {
    Map<String, Schema> topics = s.topics();
    List<SourceEvent> events = new ArrayList<>();
    Map<UUID, byte[]> hashes = new LinkedHashMap<>();
    s.jdbc()
        .query(
            "SELECT DISTINCT ON (id) id, topic, payload, aggregate_version, created_at FROM "
                + s.schema()
                + ".outbox_event WHERE aggregate_id = ? AND topic = ANY (?)"
                + " AND created_at >= ? AND created_at < ? ORDER BY id, created_at",
            rs -> {
              UUID eventId = rs.getObject("id", UUID.class);
              String topic = rs.getString("topic");
              try {
                var record = LedgerPayloadHash.decode(topics.get(topic), rs.getBytes("payload"));
                hashes.put(eventId, LedgerPayloadHash.of(record));
                events.add(
                    new SourceEvent(
                        eventId,
                        topic,
                        record,
                        rs.getLong("aggregate_version"),
                        rs.getTimestamp("created_at").toInstant()));
              } catch (IllegalArgumentException e) {
                mismatches.add(
                    json.createObjectNode()
                        .put("aggregateId", agg.toString())
                        .put("eventId", eventId.toString())
                        .put("kind", THAN_HONG));
              }
            },
            agg,
            topics.keySet().toArray(String[]::new),
            Timestamp.from(from),
            Timestamp.from(to));
    events.sort(
        Comparator.comparing(SourceEvent::createdAt).thenComparing(SourceEvent::aggregateVersion));
    return new Sampled(agg, List.copyOf(events), hashes);
  }

  private void compareLedger(
      TenantId tenant, Sampled x, Instant from, Instant now, ArrayNode mismatches) {
    Map<UUID, LedgerRef> byEvent = new HashMap<>();
    for (LedgerRef r : ledger.entries(tenant, x.aggregateId(), from, now)) {
      byEvent.putIfAbsent(r.eventId(), r);
    }
    for (SourceEvent e : x.events()) {
      LedgerRef r = byEvent.get(e.eventId());
      if (r == null) {
        if (e.createdAt().isBefore(now.minus(ledgerGrace))) {
          mismatches.add(
              json.createObjectNode()
                  .put("aggregateId", x.aggregateId().toString())
                  .put("eventId", e.eventId().toString())
                  .put("kind", THIEU_SO_CAI));
        }
        continue;
      }
      String recomputed = Jcs.hex(x.hashes().get(e.eventId()));
      if (!recomputed.equalsIgnoreCase(r.payloadHash())) {
        mismatches.add(
            json.createObjectNode()
                .put("aggregateId", x.aggregateId().toString())
                .put("eventId", e.eventId().toString())
                .put("kind", LECH_BAM)
                .put("ledgerHash", r.payloadHash())
                .put("recomputedHash", recomputed));
      }
    }
  }

  private static int distinctAggregates(ArrayNode mismatches) {
    java.util.Set<String> ids = new java.util.HashSet<>();
    mismatches.forEach(n -> ids.add(n.path("aggregateId").asText()));
    return ids.size();
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
