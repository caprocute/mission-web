package vn.viettel.okrkpi.platform.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Job R1 của DL-12 (TL-03 bảng đối soát, dòng Bản sao tổ chức): so băm tổng hợp 256 bucket của bản
 * sao tổ chức ({@code <schema>.org_unit_v}, {@code person_v}, {@code assignment_v}) với thông điệp
 * {@code KET_THUC} của {@code org.bootstrap.v1}. Lớp dùng chung cho mọi dịch vụ giữ bản sao {@code
 * org_replica}: mission-core (SVC-05) và workflow-service (DV-06-10); mỗi bên truyền {@link
 * JdbcTemplate} của nguồn dữ liệu giữ bản sao và tên schema. Phần nhận {@code KET_THUC} (Avro
 * {@code OrgBootstrap}) và lập lịch ở phía dịch vụ, để thư viện không phụ thuộc vt-events.
 *
 * <ol>
 *   <li>{@link #expect}: nhận {@code KET_THUC} (từ lệnh {@code org-republish --hashes-only} hằng
 *       đêm, hoặc từ một lần phát lại thật) và ghi {@code <schema>.reconcile_run} ở {@code
 *       DANG_CHAY} cùng băm mong đợi.
 *   <li>{@link #check}: sau thời gian lắng, tính lại băm từ bản sao theo đúng công thức của
 *       org-service (tổng modulo 2^64, {@code loại|mã|ngày hiệu lực|sourceVersion}, wiki trang 18),
 *       đi sâu vào bucket lệch và kết thúc lần chạy.
 * </ol>
 *
 * Lệch thì {@code LOI}: chi tiết ghi danh sách bucket và lệnh phát lại {@code org-republish
 * --bucket=...} (bên nhận không tự gọi được SVC-04). Một lần phát lại có thực thể khớp sau lần lệch
 * trước thì {@code DA_SUA}, {@code rows_repaired} là số thực thể đã phát. Cả hai đều gọi trong giao
 * dịch có ngữ cảnh tenant ({@code app.tenant_id}); lớp này không tự đặt ngữ cảnh.
 */
public class OrgReplicaReconciler {

  /** Mã job trong {@code reconcile_run.job} và nhãn chỉ số. */
  public static final String JOB = "R1_BAN_SAO_TO_CHUC";

  /** {@code entity_type} khi một lần chạy gồm nhiều loại thực thể (suy luận). */
  public static final String ALL_KINDS = "TAT_CA";

  /** Giá trị {@code marker} của thông điệp kết thúc loạt trong {@code org.bootstrap.v1}. */
  public static final String END_MARKER = "KET_THUC";

  /** Schema bản sao mặc định theo TL-03 ("Schema org_replica"). */
  public static final String DEFAULT_SCHEMA = "org_replica";

  private static final Logger log = LoggerFactory.getLogger(OrgReplicaReconciler.class);
  private static final Set<String> KINDS = Set.of("ORG_UNIT", "PERSON", "ASSIGNMENT");

  private final JdbcTemplate jdbc;
  private final String schema;
  private final JdbcReconcileRunStore runs;
  private final ReconcileMetrics metrics;
  private final ObjectMapper json;
  private final Clock clock;

  /**
   * @param jdbc nguồn dữ liệu giữ bản sao (giao dịch do bên gọi mở trong ngữ cảnh tenant)
   * @param schema schema bản sao, thường là {@link #DEFAULT_SCHEMA}; kiểm tên như {@link
   *     JdbcReconcileRunStore}
   */
  public OrgReplicaReconciler(
      JdbcTemplate jdbc, String schema, ReconcileMetrics metrics, ObjectMapper json, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.runs = new JdbcReconcileRunStore(jdbc, schema);
    this.schema = schema;
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Kho {@code <schema>.reconcile_run} dùng chung để job phía dịch vụ lấy lần chạy chờ so. */
  public JdbcReconcileRunStore runs() {
    return runs;
  }

  /** Mã lần chạy tất định theo {@code batchId}, nên nhận lại {@code KET_THUC} không ghi trùng. */
  public static UUID runId(UUID batchId) {
    return UUID.nameUUIDFromBytes((JOB + "|" + batchId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Ghi băm mong đợi của một loạt; {@code entities} là số thông điệp thực thể của loạt ({@code seq}
   * của {@code KET_THUC} trừ {@code BAT_DAU}). Trả {@code false} khi loạt đã được ghi.
   */
  public boolean expect(
      UUID tenant,
      UUID batchId,
      long entities,
      Map<String, Long> counts,
      Map<String, String> bucketHashes,
      Instant at) {
    Map<Integer, String> expected = new TreeMap<>();
    bucketHashes.forEach((k, v) -> expected.put(bucket(k), v));
    List<String> kinds = counts.keySet().stream().filter(KINDS::contains).sorted().toList();
    if (kinds.isEmpty()) {
      kinds = KINDS.stream().sorted().toList();
    }
    ObjectNode d = json.createObjectNode();
    d.put("batchId", batchId.toString());
    d.put("entities", Math.max(0, entities));
    d.set("kinds", json.valueToTree(kinds));
    d.set("counts", json.valueToTree(new TreeMap<>(counts)));
    d.set("expected", json.valueToTree(expected));
    return runs.insert(
        ReconcileRun.start(
            runId(batchId),
            tenant,
            JOB,
            kinds.size() == 1 ? kinds.getFirst() : ALL_KINDS,
            at,
            expected.size(),
            write(d)));
  }

  /** So một lần chạy đang chờ và kết thúc nó. */
  public RunStatus check(ReconcileRun run) {
    Instant started = clock.instant();
    JsonNode d = read(run.detail());
    Map<Integer, String> expected = new TreeMap<>();
    d.path("expected")
        .fields()
        .forEachRemaining(e -> expected.put(bucket(e.getKey()), e.getValue().asText()));
    List<String> kinds = new ArrayList<>();
    d.path("kinds").forEach(k -> kinds.add(k.asText()));
    long entities = d.path("entities").asLong();

    HashAggregate agg = new HashAggregate(HashAggregate.Mode.SUM64);
    Map<String, Long> actualCounts = new LinkedHashMap<>();
    Set<Integer> scope = expected.keySet();
    for (String kind : kinds) {
      actualCounts.put(kind, scan(kind, scope, agg));
    }
    SortedSet<Integer> mismatched = Buckets.mismatched(expected, agg.hashes(scope));

    RunStatus status;
    int repaired = 0;
    if (!mismatched.isEmpty()) {
      status = RunStatus.LOI;
    } else if (entities > 0
        && runs.lastFinished(JOB, run.entityType(), run.startedAt())
            .map(r -> r.status() == RunStatus.LOI)
            .orElse(false)) {
      status = RunStatus.DA_SUA;
      repaired = (int) Math.min(Integer.MAX_VALUE, entities);
    } else {
      status = RunStatus.KHOP;
    }

    ObjectNode out = json.createObjectNode();
    out.set("actualCounts", json.valueToTree(actualCounts));
    out.set("mismatched", json.valueToTree(mismatched));
    if (!mismatched.isEmpty()) {
      out.put("replay", replay(run, kinds, mismatched));
      log.warn(
          "Đối soát R1 lệch: tenant={} batch={} bucketLech={}",
          run.tenantId(),
          d.path("batchId").asText(),
          mismatched.size());
    }
    Instant now = clock.instant();
    runs.finish(run.id(), status, mismatched.size(), repaired, write(out), now);
    metrics.finished(JOB, status, mismatched.size(), repaired, Duration.between(started, now));
    return status;
  }

  private long scan(String kind, Set<Integer> scope, HashAggregate agg) {
    String sql =
        switch (kind) {
          case "ORG_UNIT" -> "SELECT id, valid_from, source_version FROM " + schema + ".org_unit_v";
          case "ASSIGNMENT" ->
              "SELECT id, valid_from, source_version FROM " + schema + ".assignment_v";
          case "PERSON" ->
              "SELECT id, NULL::date AS valid_from, source_version FROM " + schema + ".person_v";
          default -> throw new IllegalArgumentException("Loại thực thể không hỗ trợ: " + kind);
        };
    long[] n = {0};
    jdbc.query(
        con -> {
          var ps = con.prepareStatement(sql);
          ps.setFetchSize(5_000);
          return ps;
        },
        rs -> {
          UUID id = rs.getObject(1, UUID.class);
          int b = Buckets.of(id);
          if (scope.contains(b)) {
            Date from = rs.getDate(2);
            agg.addTo(
                b,
                RowHash.canonical(
                    kind, id, from == null ? null : from.toLocalDate(), rs.getLong(3)));
            n[0]++;
          }
        });
    return n[0];
  }

  private static String replay(ReconcileRun run, List<String> kinds, SortedSet<Integer> buckets) {
    String entity =
        kinds.size() == 3
            ? ""
            : " --entity="
                + kinds.stream()
                    .map(
                        k ->
                            switch (k) {
                              case "ORG_UNIT" -> "unit";
                              case "PERSON" -> "person";
                              default -> "assignment";
                            })
                    .collect(Collectors.joining(","));
    return "org-republish --tenant="
        + run.tenantId()
        + entity
        + " --bucket="
        + buckets.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  private static int bucket(String key) {
    int b = Integer.parseInt(key.trim());
    if (b < 0 || b >= Buckets.COUNT) {
      throw new IllegalArgumentException("Bucket ngoài khoảng 0..255: " + key);
    }
    return b;
  }

  private JsonNode read(String s) {
    try {
      return json.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("detail của reconcile_run không phải JSON", e);
    }
  }

  private String write(JsonNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Các bucket của tập mã (dùng trong kiểm thử của dịch vụ). */
  public static SortedSet<Integer> bucketsOf(List<UUID> ids) {
    return ids.stream().map(Buckets::of).collect(Collectors.toCollection(TreeSet::new));
  }
}
