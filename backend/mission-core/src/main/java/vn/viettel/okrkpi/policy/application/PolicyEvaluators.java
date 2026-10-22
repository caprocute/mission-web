package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bộ đánh giá đã biên dịch của một phiên bản Policy Pack, đệm theo phiên bản và dấu vân tay nội
 * dung (manifest, quy tắc, bảng quyết định), dùng chung cho chạy kiểm thử (CORE-05-07) và tính thử
 * một ca (CORE-05-08). Phiên bản nháp sửa quy tắc hay bảng thì dấu vân tay đổi và bộ đánh giá được
 * biên dịch lại; đệm giới hạn {@value #MAX_ENTRIES} mục, bỏ mục dùng lâu nhất.
 */
public class PolicyEvaluators {

  static final int MAX_ENTRIES = 64;

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final PolicyPackRepository packs;
  private final PolicyDecisionTableService tables;
  private final ObjectMapper json;
  private final CelEngine cel =
      new CelEngine(
          StaticWorkingCalendar.weekendsOnly(),
          new CelSandboxOptions(
              CelSandboxOptions.DEFAULT_MAX_EXPRESSION_LENGTH,
              CelSandboxOptions.DEFAULT_MAX_AST_DEPTH,
              CelSandboxOptions.DEFAULT_MAX_AST_NODE_COUNT,
              CelSandboxOptions.DEFAULT_MAX_COMPREHENSION_ITERATIONS,
              Duration.ofSeconds(2),
              CelSandboxOptions.DEFAULT_PROGRAM_CACHE_MAX_SIZE));
  private final Map<String, PolicyEvaluator> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PolicyEvaluator> eldest) {
          return size() > MAX_ENTRIES;
        }
      };
  private final AtomicLong compiled = new AtomicLong();

  public PolicyEvaluators(
      PolicyPackRepository packs, PolicyDecisionTableService tables, ObjectMapper json) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.json = Objects.requireNonNull(json, "json");
  }

  /** Bộ đánh giá của phiên bản; lỗi biên dịch ném {@code POLICY-422-CEL-TYPE}. */
  PolicyEvaluator of(TenantId tenant, PolicyPackVersion v) {
    List<PolicyRule> rules = packs.rules(tenant, v.id());
    List<DecisionTable> stored = packs.decisionTables(tenant, v.id());
    String key = v.id() + ":" + fingerprint(v, rules, stored);
    synchronized (cache) {
      PolicyEvaluator hit = cache.get(key);
      if (hit != null) {
        return hit;
      }
    }
    Map<String, DecisionTableSpec> specs = new LinkedHashMap<>();
    for (DecisionTable t : stored) {
      specs.put(t.tableKey(), tables.spec(t));
    }
    PolicyEvaluator evaluator =
        new PolicyEvaluator(cel, json, read(v.manifestJson()), rules, specs);
    compiled.incrementAndGet();
    synchronized (cache) {
      cache.put(key, evaluator);
    }
    return evaluator;
  }

  /** Số lần đã biên dịch (đo tỉ lệ trúng đệm). */
  long compiledCount() {
    return compiled.get();
  }

  private static String fingerprint(
      PolicyPackVersion v, List<PolicyRule> rules, List<DecisionTable> stored) {
    MessageDigest sha;
    try {
      sha = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    update(sha, v.manifestJson());
    rules.stream()
        .sorted(Comparator.comparing(PolicyRule::ruleKey))
        .forEach(
            r -> {
              update(sha, r.ruleKey());
              update(sha, r.expression());
              update(sha, r.resultType());
              update(sha, r.roundingJson());
              update(sha, r.paramsJson());
              update(sha, String.valueOf(r.evalOrder()));
              update(sha, String.valueOf(r.overridable()));
            });
    stored.stream()
        .sorted(Comparator.comparing(DecisionTable::tableKey))
        .forEach(
            t -> {
              update(sha, t.tableKey());
              update(sha, t.hitPolicy());
              update(sha, t.rulesJson());
            });
    return HexFormat.of().formatHex(sha.digest());
  }

  private static void update(MessageDigest sha, String s) {
    byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    sha.update(Integer.toString(b.length).getBytes(StandardCharsets.US_ASCII));
    sha.update((byte) ':');
    sha.update(b);
  }

  private Map<String, Object> read(String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung JSON đã lưu không đọc được", e);
    }
  }
}
