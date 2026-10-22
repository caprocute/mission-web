package vn.viettel.okrkpi.policy.application;

import static vn.viettel.okrkpi.policy.application.PolicyPackService.TX;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.policy.application.port.GoldenTestCaseRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.GoldenCaseStatus;
import vn.viettel.okrkpi.policy.domain.GoldenTestCase;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * So sánh hai phiên bản và tính thử một ca vàng (CORE-05-08, QT-15, SCR-1501, SCR-1503): {@code
 * diffPolicyPackVersions} và {@code evaluatePolicyVersion}. Tính thử chạy trong MOD-05 bằng cùng bộ
 * đánh giá cel-java và bảng quyết định của chạy kiểm thử (đệm chung {@link PolicyEvaluators}),
 * không ghi dữ liệu nghiệp vụ.
 */
public class PolicyPreviewService {

  /** Loại thành phần thay đổi của {@code PolicyVersionDiff}. */
  public enum Kind {
    RULE,
    PARAM,
    DECISION_TABLE,
    WORKFLOW_BINDING,
    MANIFEST
  }

  /** Kiểu thay đổi. */
  public enum ChangeType {
    THEM,
    SUA,
    XOA
  }

  /** Một khác biệt; {@code before} rỗng khi thêm, {@code after} rỗng khi xoá. */
  public record Change(
      Kind kind, String key, String path, ChangeType change, Object before, Object after) {}

  /** Kết quả so sánh. */
  public record VersionDiff(String base, String target, List<Change> changes) {}

  /** Một bước của vết tính. */
  public record TraceStep(String ruleKey, Object value) {}

  /** Kết quả tính thử một ca. */
  public record Evaluation(
      String caseCode,
      Map<String, Object> outputs,
      String ratingLevel,
      List<TraceStep> trace,
      String explanation) {}

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Khối manifest có cách so riêng hoặc không so (siêu dữ liệu phiên bản). */
  private static final Set<String> MANIFEST_SKIP =
      Set.of("metadata", "params", "workflow_bindings");

  private static final Pattern VAR = Pattern.compile("\\{([^{}]*)}");

  private final PolicyPackRepository packs;
  private final GoldenTestCaseRepository cases;
  private final PolicyDecisionTableService tables;
  private final PolicyEvaluators evaluators;
  private final ObjectMapper json;

  public PolicyPreviewService(
      PolicyPackRepository packs,
      GoldenTestCaseRepository cases,
      PolicyDecisionTableService tables,
      PolicyEvaluators evaluators,
      ObjectMapper json) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.cases = Objects.requireNonNull(cases, "cases");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.evaluators = Objects.requireNonNull(evaluators, "evaluators");
    this.json = Objects.requireNonNull(json, "json");
  }

  /**
   * Khác biệt của phiên bản {@code version} so với {@code base}: quy tắc, tham số, bảng quyết định
   * (dòng so theo thứ tự và nội dung ô), tham số luồng duyệt, các khối manifest khác.
   *
   * @throws NoSuchElementException gói hoặc một trong hai phiên bản không có (404)
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public VersionDiff diff(TenantId tenant, UUID packId, String version, String base) {
    packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion target =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion from =
        packs.findVersion(tenant, packId, base).orElseThrow(NoSuchElementException::new);
    List<Change> changes = new ArrayList<>();
    Map<String, Object> mBase = read(from.manifestJson());
    Map<String, Object> mTarget = read(target.manifestJson());
    compareSection(Kind.PARAM, mBase.get("params"), mTarget.get("params"), changes);
    compareSection(
        Kind.WORKFLOW_BINDING,
        mBase.get("workflow_bindings"),
        mTarget.get("workflow_bindings"),
        changes);
    Set<String> sections = new LinkedHashSet<>(mBase.keySet());
    sections.addAll(mTarget.keySet());
    for (String s : sections) {
      if (!MANIFEST_SKIP.contains(s)) {
        compareValue(Kind.MANIFEST, s, "", mBase.get(s), mTarget.get(s), changes);
      }
    }
    compareRules(packs.rules(tenant, from.id()), packs.rules(tenant, target.id()), changes);
    compareTables(
        specs(packs.decisionTables(tenant, from.id())),
        specs(packs.decisionTables(tenant, target.id())),
        changes);
    return new VersionDiff(from.version(), target.version(), changes);
  }

  /**
   * Tính một ca của bộ vàng (gói hoặc gói cha) trên cel-java và bảng quyết định, kèm vết tính và
   * câu giải thích theo mẫu của dòng quyết định.
   *
   * @throws NoSuchElementException gói hoặc phiên bản không có (404)
   * @throws BusinessException {@code POLICY-422-CASE-NOT-GOLDEN}, {@code POLICY-422-CEL-TYPE}
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Evaluation evaluateCase(TenantId tenant, UUID packId, String version, String caseCode) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion v =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    GoldenTestCase c =
        golden(tenant, pack, caseCode)
            .orElseThrow(
                () ->
                    new BusinessException(
                        "POLICY-422-CASE-NOT-GOLDEN",
                        "Ca " + caseCode + " không thuộc bộ ca kiểm thử của gói.",
                        List.of(
                            new ProblemFieldError(
                                "caseCode",
                                "Chỉ tính thử được ca trong bộ ca kiểm thử của gói",
                                "SCR-1501"))));
    PolicyEvaluator evaluator = evaluators.of(tenant, v);
    PolicyEvaluator.Outcome outcome;
    try {
      outcome = evaluator.evaluate(read(c.inputJson()), true);
    } catch (RuntimeException e) {
      throw new BusinessException(
          "POLICY-422-CEL-TYPE",
          "Không tính được ca " + caseCode + ": " + e.getMessage(),
          List.of(new ProblemFieldError("caseCode", String.valueOf(e.getMessage()), "BR-15-04")),
          "MSG-15-01");
    }
    Map<String, Object> outputs = new LinkedHashMap<>(outcome.outputs());
    List<Map<String, Object>> tasks = tasks(outcome.tasks());
    if (!tasks.isEmpty()) {
      outputs.put("nhiem_vu", tasks);
    }
    List<Map<String, Object>> groups = groups(outcome.groups());
    if (!groups.isEmpty()) {
      outputs.put("nhom", groups);
    }
    String level =
        outputs.get(evaluator.levelKey()) instanceof String s
                && DecisionTableSpec.LEVELS.contains(s)
            ? s
            : null;
    List<TraceStep> trace =
        outcome.steps().stream().map(s -> new TraceStep(s.key(), s.value())).toList();
    return new Evaluation(
        c.caseCode(),
        outputs,
        level,
        trace,
        explanation(outcome, level, withVersion(outcome.context(), v.version())));
  }

  private Optional<GoldenTestCase> golden(TenantId tenant, PolicyPack pack, String caseCode) {
    Optional<GoldenTestCase> own =
        cases.findCaseByCode(tenant, pack.id(), caseCode).filter(PolicyPreviewService::usable);
    if (own.isPresent() || pack.parentPackId() == null) {
      return own;
    }
    return cases
        .findCaseByCode(tenant, pack.parentPackId(), caseCode)
        .filter(PolicyPreviewService::usable);
  }

  private static boolean usable(GoldenTestCase c) {
    return c.status() != GoldenCaseStatus.NGUNG;
  }

  /**
   * Kết quả từng nhiệm vụ theo {@code PolicyEvaluateOutputs.nhiem_vu}; thiếu khoá bắt buộc thì bỏ.
   */
  private static List<Map<String, Object>> tasks(List<Map<String, Object>> tasks) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> t : tasks) {
      if (t.get("ma") == null || !(t.get(PolicyEvaluator.KPI_KEY) instanceof Number)) {
        return List.of();
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ma", String.valueOf(t.get("ma")));
      if (t.get("nhom") != null) {
        m.put("nhom", String.valueOf(t.get("nhom")));
      }
      for (String k : List.of("A", "B", "C")) {
        if (t.get(k) instanceof Number n) {
          m.put(k, n);
        }
      }
      m.put(PolicyEvaluator.KPI_KEY, t.get(PolicyEvaluator.KPI_KEY));
      out.add(m);
    }
    return out;
  }

  /** Kết quả từng nhóm theo {@code PolicyEvaluateOutputs.nhom}; thiếu khoá bắt buộc thì bỏ. */
  private static List<Map<String, Object>> groups(List<Map<String, Object>> groups) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> g : groups) {
      if (g.get("ma") == null
          || !(g.get("kpi_nhom") instanceof Number)
          || !(g.get("diem_toi_da") instanceof Number)
          || !(g.get(PolicyEvaluator.SCORE_KEY) instanceof Number)) {
        return List.of();
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", String.valueOf(g.get("ma")));
      m.put("kpi_nhom", g.get("kpi_nhom"));
      m.put("diem_toi_da", g.get("diem_toi_da"));
      m.put(PolicyEvaluator.SCORE_KEY, g.get(PolicyEvaluator.SCORE_KEY));
      out.add(m);
    }
    return out;
  }

  /** Lời giải thích của dòng quyết định cho mức cuối, thay biến {@code {bien}} bằng giá trị. */
  static String explanation(
      PolicyEvaluator.Outcome outcome, String level, Map<String, Object> context) {
    if (level == null) {
      return null;
    }
    List<DecisionTableSpec.Row> matched = new ArrayList<>(outcome.rows().values());
    for (int i = matched.size() - 1; i >= 0; i--) {
      DecisionTableSpec.Row r = matched.get(i);
      if (level.equals(r.ratingLevel()) && r.explanation() != null && !r.explanation().isBlank()) {
        Map<String, Object> vars = new LinkedHashMap<>(context);
        if (r.clause() != null && !r.clause().isBlank()) {
          vars.putIfAbsent("dieu_khoan", r.clause());
        }
        return render(r.explanation(), vars);
      }
    }
    return null;
  }

  private static Map<String, Object> withVersion(Map<String, Object> context, String version) {
    Map<String, Object> vars = new LinkedHashMap<>(context);
    vars.putIfAbsent("phien_ban", version);
    return vars;
  }

  static String render(String template, Map<String, Object> context) {
    Matcher m = VAR.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      Object v = context.get(m.group(1).strip());
      m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : format(v)));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** Số in kiểu Việt Nam: tối đa hai chữ số thập phân, dấu phẩy thập phân. */
  static String format(Object v) {
    if (v instanceof Number n) {
      BigDecimal d =
          new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
      return d.toPlainString().replace('.', ',');
    }
    return String.valueOf(v);
  }

  // ------------------------------------------------------------------ so sánh

  private Map<String, DecisionTableSpec> specs(List<DecisionTable> stored) {
    Map<String, DecisionTableSpec> out = new LinkedHashMap<>();
    stored.forEach(t -> out.put(t.tableKey(), tables.spec(t)));
    return out;
  }

  /** So từng khoá của một khối map (tham số, ràng buộc luồng duyệt). */
  private static void compareSection(Kind kind, Object base, Object target, List<Change> out) {
    Map<?, ?> b = base instanceof Map<?, ?> m ? m : Map.of();
    Map<?, ?> t = target instanceof Map<?, ?> m ? m : Map.of();
    Set<Object> keys = new LinkedHashSet<>(b.keySet());
    keys.addAll(t.keySet());
    for (Object k : keys) {
      compareValue(kind, String.valueOf(k), "", b.get(k), t.get(k), out);
    }
  }

  /** So đệ quy theo map; danh sách và giá trị đơn so nguyên khối. */
  private static void compareValue(
      Kind kind, String key, String path, Object before, Object after, List<Change> out) {
    if (Objects.equals(normalize(before), normalize(after))) {
      return;
    }
    if (before == null || after == null) {
      out.add(
          new Change(
              kind,
              key,
              path.isEmpty() ? null : path,
              before == null ? ChangeType.THEM : ChangeType.XOA,
              before,
              after));
      return;
    }
    if (before instanceof Map<?, ?> b && after instanceof Map<?, ?> a) {
      Set<Object> keys = new LinkedHashSet<>(b.keySet());
      keys.addAll(a.keySet());
      for (Object k : keys) {
        String p = path.isEmpty() ? String.valueOf(k) : path + "." + k;
        compareValue(kind, key, p, b.get(k), a.get(k), out);
      }
      return;
    }
    out.add(new Change(kind, key, path.isEmpty() ? null : path, ChangeType.SUA, before, after));
  }

  /** Số so theo giá trị (1 và 1.0 là một), map và danh sách so đệ quy. */
  private static Object normalize(Object v) {
    if (v instanceof Number n) {
      return new BigDecimal(n.toString()).stripTrailingZeros();
    }
    if (v instanceof Map<?, ?> m) {
      Map<Object, Object> out = new LinkedHashMap<>();
      m.forEach((k, x) -> out.put(k, normalize(x)));
      return out;
    }
    if (v instanceof List<?> l) {
      return l.stream().map(PolicyPreviewService::normalize).toList();
    }
    return v;
  }

  private void compareRules(List<PolicyRule> base, List<PolicyRule> target, List<Change> out) {
    Map<String, PolicyRule> b = new LinkedHashMap<>();
    base.forEach(r -> b.put(r.ruleKey(), r));
    Map<String, PolicyRule> t = new LinkedHashMap<>();
    target.forEach(r -> t.put(r.ruleKey(), r));
    Set<String> keys = new LinkedHashSet<>(b.keySet());
    keys.addAll(t.keySet());
    for (String k : keys) {
      PolicyRule before = b.get(k);
      PolicyRule after = t.get(k);
      if (before == null || after == null) {
        out.add(
            new Change(
                Kind.RULE,
                k,
                "expression",
                before == null ? ChangeType.THEM : ChangeType.XOA,
                before == null ? null : before.expression(),
                after == null ? null : after.expression()));
        continue;
      }
      compareValue(Kind.RULE, k, "expression", before.expression(), after.expression(), out);
      compareValue(Kind.RULE, k, "resultType", before.resultType(), after.resultType(), out);
      compareValue(
          Kind.RULE, k, "rounding", read(before.roundingJson()), read(after.roundingJson()), out);
      compareValue(Kind.RULE, k, "evalOrder", before.evalOrder(), after.evalOrder(), out);
      compareValue(Kind.RULE, k, "legalRef", before.legalRef(), after.legalRef(), out);
      compareValue(Kind.RULE, k, "overridable", before.overridable(), after.overridable(), out);
      compareValue(
          Kind.PARAM, k, "params", read(before.paramsJson()), read(after.paramsJson()), out);
    }
  }

  private static void compareTables(
      Map<String, DecisionTableSpec> base,
      Map<String, DecisionTableSpec> target,
      List<Change> out) {
    Set<String> keys = new LinkedHashSet<>(base.keySet());
    keys.addAll(target.keySet());
    for (String k : keys) {
      DecisionTableSpec before = base.get(k);
      DecisionTableSpec after = target.get(k);
      if (before == null || after == null) {
        out.add(
            new Change(
                Kind.DECISION_TABLE,
                k,
                null,
                before == null ? ChangeType.THEM : ChangeType.XOA,
                before == null ? null : rows(before),
                after == null ? null : rows(after)));
        continue;
      }
      compareValue(Kind.DECISION_TABLE, k, "hitPolicy", before.hitPolicy(), after.hitPolicy(), out);
      List<Map<String, Object>> rb = rows(before);
      List<Map<String, Object>> ra = rows(after);
      for (int i = 0; i < Math.max(rb.size(), ra.size()); i++) {
        compareValue(
            Kind.DECISION_TABLE,
            k,
            "rules[" + i + "]",
            i < rb.size() ? rb.get(i) : null,
            i < ra.size() ? ra.get(i) : null,
            out);
      }
    }
  }

  private static List<Map<String, Object>> rows(DecisionTableSpec spec) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (DecisionTableSpec.Row r : spec.rows()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("inputs", new LinkedHashMap<>(r.inputs()));
      m.put("ratingLevel", r.ratingLevel());
      m.put("explanation", r.explanation());
      out.add(m);
    }
    return out;
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
