package vn.viettel.okrkpi.scoring.application.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.scoring.domain.AnnualInput;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TraceStep;

/**
 * Chuỗi tính điểm của một hồ sơ theo Policy Pack đã ghim (DV-07-03, QT-07 bước 11, QT-09): phạm vi
 * {@code nhiem_vu} cho từng nhiệm vụ (A, B, C, KPI nhiệm vụ, BR-09-01, BR-09-04), phạm vi {@code
 * nhom} cho từng nhóm của tham số {@code nhom} (KPI nhóm, điểm nhóm, BR-09-03), rồi các phạm vi cấp
 * hồ sơ của {@code definitions} theo {@code eval_order} (điểm, tỷ lệ vượt). Bảng quyết định chỉ đọc
 * biến đầu vào của hồ sơ (như quy đổi tiêu chí chung, BR-09-05) chạy ngay khi đủ cột; kết quả ghi
 * vào biến cùng tên đầu ra và các biến {@code ngu_canh} có {@code nguon: dmn <bảng>}. Phạm vi chứa
 * biến {@code classification.muc_cuoi} và bảng đọc kết quả tính (xếp loại) thuộc DV-07-05 nên không
 * chạy ở đây.
 *
 * <p>Mỗi lần gọi CEL hay tra bảng là một bước của vết tính: mã quy tắc, căn cứ, giá trị vào (chỉ
 * các biến biểu thức tham chiếu; danh sách thu về mã và kết quả; {@code tham_so} thu về đúng khoá
 * dùng) và giá trị ra. Bước của phạm vi nhiệm vụ mang {@code task_id} trong giá trị vào. Không có
 * dữ liệu C2 trong đầu vào nên vết cũng không có. Cùng đầu vào và cùng phiên bản luôn cho cùng
 * {@code result_hash}: kết quả không chứa thời điểm hay mã ngẫu nhiên.
 */
public final class ScoreCalculator {

  /** Biến điểm của hồ sơ (BR-09-03). */
  public static final String SCORE_KEY = "diem";

  static final String TASKS = ScoringInput.TASKS;
  static final String GROUPS = "nhom";
  static final String TASK_RESULTS = "ket_qua_nhiem_vu";
  static final String GROUP_RESULTS = "ket_qua_nhom";
  static final String KPI_KEY = "kpi_nhiem_vu";
  static final String TASK_ID = "task_id";
  static final String ANNUAL = "nam";
  static final String QUARTERS = AnnualInput.QUARTERS;

  /** Biến điểm năm (BR-10-02). */
  public static final String ANNUAL_SCORE_KEY = "diem_nam";

  private static final Set<String> IDENTITY = Set.of("ma", "nhom", TASK_ID);
  private static final Pattern PARAM_PATH = Pattern.compile("tham_so((?:\\.[A-Za-z_][\\w]*)+)");

  /**
   * Kết quả tính.
   *
   * @param totalScore điểm {@code diem} đã làm tròn theo gói
   * @param outputs biến cấp hồ sơ đã tính (kể cả kết quả bảng quyết định)
   * @param tasks kết quả từng nhiệm vụ theo thứ tự đầu vào
   * @param groups kết quả từng nhóm
   * @param context mọi biến sau khi tính, dùng cho bước xếp loại (DV-07-05)
   */
  public record Calculation(
      CanonicalDoc input,
      CanonicalDoc result,
      BigDecimal totalScore,
      Map<String, Object> outputs,
      List<Map<String, Object>> tasks,
      List<Map<String, Object>> groups,
      List<TraceStep> trace,
      Map<String, Object> context) {}

  /** Tính không ghi đè tham số. */
  public Calculation calculate(CompiledPolicy policy, ScoringInput input) {
    return calculate(policy, input, Map.of());
  }

  /**
   * Tính một hồ sơ.
   *
   * @param overrides giá trị ghi đè tham số theo khoá phẳng ({@code htxs.nguong_ty_le_vuot}), chỉ
   *     dùng cho ca kiểm thử của gói
   * @throws IllegalStateException gói không có phạm vi nhiệm vụ hoặc không tính ra {@code diem}
   */
  public Calculation calculate(
      CompiledPolicy policy, ScoringInput input, Map<String, Object> overrides) {
    return calculate(policy, input.document(), input.variables(), overrides);
  }

  /**
   * Tính trên biến đầu vào theo kiểu của gói (bộ ca mẫu của Policy Pack ghi thẳng phần tử {@code
   * NhiemVu}); phần tử thiếu {@code ma} được đặt {@code NV-<thứ tự>}, thiếu {@code task_id} lấy mã.
   */
  Calculation calculate(
      CompiledPolicy policy,
      CanonicalDoc inputDoc,
      Map<String, Object> variables,
      Map<String, Object> overrides) {
    Plan plan = Plan.of(policy);
    Run run = new Run(policy, plan);
    Map<String, Object> ts = params(policy.params(), overrides);
    Map<String, Object> vars = new HashMap<>(variables);
    vars.put("tham_so", ts);

    List<Map<String, Object>> tasks = new ArrayList<>();
    if (vars.get(TASKS) instanceof List<?> raw) {
      for (Object o : raw) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nv = new LinkedHashMap<>((Map<String, Object>) o);
        nv.putIfAbsent("ma", "NV-" + tasks.size());
        nv.putIfAbsent(TASK_ID, nv.get("ma"));
        tasks.add(nv);
      }
    }
    vars.put(TASKS, tasks);
    List<Map<String, Object>> taskResults = new ArrayList<>();
    for (Map<String, Object> nv : tasks) {
      Map<String, Object> v = new HashMap<>(Map.of("nv", nv, "tham_so", ts));
      Map<String, Object> r = run.scope(plan.tasks, v, String.valueOf(nv.get(TASK_ID)));
      Map<String, Object> result = new LinkedHashMap<>(nv);
      result.putAll(r);
      taskResults.add(result);
    }
    List<Map<String, Object>> groupResults = new ArrayList<>();
    if (ts.get(GROUPS) instanceof List<?> groups) {
      for (Object o : groups) {
        if (!(o instanceof Map<?, ?> group)) {
          continue;
        }
        List<?> members =
            group.get("nhom_nhiem_vu") instanceof List<?> l ? l : List.of(group.get("ma"));
        List<Object> kpis =
            taskResults.stream()
                .filter(t -> members.contains(t.get("nhom")))
                .map(t -> t.get(KPI_KEY))
                .toList();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("ma", group.get("ma"));
        g.put("diem_toi_da", group.get("diem_toi_da"));
        g.put("ds_kpi", kpis);
        Map<String, Object> r =
            run.scope(plan.groups, new HashMap<>(Map.of("g", g, "tham_so", ts)), null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ma", group.get("ma"));
        result.put("diem_toi_da", group.get("diem_toi_da"));
        result.putAll(r);
        groupResults.add(result);
      }
    }
    vars.put(TASK_RESULTS, taskResults);
    vars.put(GROUP_RESULTS, groupResults);
    for (List<String> scope : plan.profile) {
      run.decide(vars);
      run.optional(scope, vars);
    }
    run.decide(vars);
    run.optional(plan.extra, vars);
    run.decide(vars);

    if (!(run.outputs.get(SCORE_KEY) instanceof Number score)) {
      throw new IllegalStateException("Gói quy tắc không tính ra điểm " + SCORE_KEY);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("policy_version", policy.pinned().version());
    doc.put("policy_content_hash", policy.contentHash());
    doc.putAll(new TreeMap<>(run.outputs));
    doc.put(TASKS, project(taskResults, plan.resultKeys));
    doc.put(GROUPS, groupResults);
    vars.putAll(run.outputs);
    return new Calculation(
        inputDoc,
        CanonicalDoc.of(doc),
        BigDecimal.valueOf(score.doubleValue()).stripTrailingZeros(),
        Map.copyOf(run.outputs),
        List.copyOf(taskResults),
        List.copyOf(groupResults),
        List.copyOf(run.trace),
        vars);
  }

  /**
   * Tính điểm năm trên kết quả bốn quý (DV-07-07, QT-10): quy đổi tiêu chí chung năm bằng bảng chỉ
   * đọc {@code inputs_nam}, rồi phạm vi {@code nam} theo {@code eval_order} (điểm năm theo {@code
   * nam.cach_tinh}, số quý không hoàn thành, tỷ lệ vượt năm, cờ bố trí lại). Không tính lại quý
   * (BR-10-06): {@code ket_qua_quy} là kết quả quý đã quyết định.
   *
   * @throws IllegalStateException gói không có phạm vi năm hoặc không tính ra {@code diem_nam}
   */
  public Calculation calculateAnnual(
      CompiledPolicy policy,
      CanonicalDoc inputDoc,
      Map<String, Object> variables,
      Map<String, Object> overrides) {
    Plan plan = Plan.annual(policy);
    Run run = new Run(policy, plan);
    Map<String, Object> vars = new HashMap<>(variables);
    vars.put("tham_so", params(policy.params(), overrides));
    run.decide(vars);
    run.optional(plan.profile.get(0), vars);
    run.decide(vars);
    if (!(run.outputs.get(ANNUAL_SCORE_KEY) instanceof Number score)) {
      throw new IllegalStateException("Gói quy tắc không tính ra điểm " + ANNUAL_SCORE_KEY);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("policy_version", policy.pinned().version());
    doc.put("policy_content_hash", policy.contentHash());
    doc.putAll(new TreeMap<>(run.outputs));
    if (vars.get(QUARTERS) != null) {
      doc.put(QUARTERS, vars.get(QUARTERS));
    }
    vars.putAll(run.outputs);
    return new Calculation(
        inputDoc,
        CanonicalDoc.of(doc),
        BigDecimal.valueOf(score.doubleValue()).stripTrailingZeros(),
        Map.copyOf(run.outputs),
        List.of(),
        List.of(),
        List.copyOf(run.trace),
        vars);
  }

  /**
   * Tham số mặc định ({@code params.*.mac_dinh}) ghép giá trị ghi đè theo khoá phẳng, rồi lồng theo
   * dấu chấm như MOD-05.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> params(Map<String, Object> declared, Map<String, Object> overrides) {
    Map<String, Object> flat = new LinkedHashMap<>();
    declared.forEach((k, v) -> flat.put(k, v instanceof Map<?, ?> m ? m.get("mac_dinh") : v));
    if (overrides != null) {
      flat.putAll(overrides);
    }
    Map<String, Object> nested = new LinkedHashMap<>();
    flat.forEach(
        (k, v) -> {
          String[] p = k.split("\\.");
          Map<String, Object> m = nested;
          for (int i = 0; i < p.length - 1; i++) {
            m = (Map<String, Object>) m.computeIfAbsent(p[i], x -> new LinkedHashMap<>());
          }
          m.put(p[p.length - 1], v);
        });
    return nested;
  }

  /** Đúng các khoá {@code tham_so} mà biểu thức đọc, theo đường dẫn chấm, theo thứ tự xuất hiện. */
  static Map<String, Object> usedParams(String expression, Object ts) {
    Map<String, Object> out = new LinkedHashMap<>();
    Matcher m = PARAM_PATH.matcher(expression);
    while (m.find()) {
      String path = m.group(1).substring(1);
      Object v = ts;
      for (String p : path.split("\\.")) {
        v = v instanceof Map<?, ?> map ? map.get(p) : null;
      }
      if (v != null) {
        out.putIfAbsent("tham_so." + path, v);
      }
    }
    return out;
  }

  /** Danh sách bản ghi thu về khoá định danh và khoá kết quả. */
  static List<Map<String, Object>> project(List<?> list, Set<String> resultKeys) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object o : list) {
      if (o instanceof Map<?, ?> m) {
        Map<String, Object> p = new LinkedHashMap<>();
        m.forEach(
            (k, v) -> {
              String key = String.valueOf(k);
              if (IDENTITY.contains(key) || resultKeys.contains(key)) {
                p.put(key, v);
              }
            });
        out.add(p);
      }
    }
    return out;
  }

  /** Thứ tự chạy suy từ manifest và quy tắc của phiên bản. */
  private record Plan(
      List<String> tasks,
      List<String> groups,
      List<List<String>> profile,
      List<String> extra,
      Set<String> resultKeys,
      List<String> tables,
      Map<String, List<String>> aliases,
      Map<String, String> tableRefs) {

    @SuppressWarnings("unchecked")
    static Plan of(CompiledPolicy policy) {
      Map<String, Object> manifest = policy.manifest();
      Map<String, CompiledPolicy.Rule> rules = policy.rules();
      String finalKey =
          manifest.get("classification") instanceof Map<?, ?> cls
                  && cls.get("muc_cuoi") instanceof String k
              ? k
              : null;
      Map<String, List<String>> scopes = new LinkedHashMap<>();
      Set<String> defined = new HashSet<>();
      for (String block : List.of("definitions", "definitions_nam")) {
        if (manifest.get(block) instanceof Map<?, ?> m) {
          m.forEach(
              (scope, defs) -> {
                if (defs instanceof Map<?, ?> d) {
                  List<String> keys = new ArrayList<>();
                  d.keySet().forEach(k -> keys.add(String.valueOf(k)));
                  defined.addAll(keys);
                  if (block.equals("definitions")) {
                    scopes.put(String.valueOf(scope), ordered(keys, rules));
                  }
                }
              });
        }
      }
      if (!scopes.containsKey(TASKS)) {
        throw new IllegalStateException("Gói quy tắc không có phạm vi " + TASKS);
      }
      List<List<String>> profile = new ArrayList<>();
      scopes.entrySet().stream()
          .filter(e -> !e.getKey().equals(TASKS) && !e.getKey().equals(GROUPS))
          .filter(e -> finalKey == null || !e.getValue().contains(finalKey))
          .sorted(Comparator.comparingInt(e -> first(e.getValue(), rules)))
          .forEach(e -> profile.add(e.getValue()));
      List<String> extra =
          ordered(rules.keySet().stream().filter(k -> !defined.contains(k)).toList(), rules);
      Set<String> resultKeys = new HashSet<>(scopes.get(TASKS));
      resultKeys.addAll(scopes.getOrDefault(GROUPS, List.of()));

      Map<String, String> tableRefs = tableRefs(manifest);
      List<String> tables = inputTables(policy, "inputs", tableRefs);
      Map<String, List<String>> aliases = aliases(manifest);
      return new Plan(
          scopes.get(TASKS),
          scopes.getOrDefault(GROUPS, List.of()),
          profile,
          extra,
          resultKeys,
          tables,
          aliases,
          tableRefs);
    }

    /**
     * Kế hoạch xếp loại năm (DV-07-07): phạm vi {@code nam} của {@code definitions_nam}, bảng chỉ
     * đọc biến của {@code inputs_nam} (quy đổi tiêu chí chung năm).
     */
    static Plan annual(CompiledPolicy policy) {
      Map<String, Object> manifest = policy.manifest();
      List<String> keys = new ArrayList<>();
      if (manifest.get("definitions_nam") instanceof Map<?, ?> m
          && m.get(ANNUAL) instanceof Map<?, ?> d) {
        d.keySet().forEach(k -> keys.add(String.valueOf(k)));
      }
      List<String> scope = ordered(keys, policy.rules());
      if (scope.isEmpty()) {
        throw new IllegalStateException("Gói quy tắc không có phạm vi " + ANNUAL);
      }
      Map<String, String> tableRefs = tableRefs(manifest);
      return new Plan(
          List.of(),
          List.of(),
          List.of(scope),
          List.of(),
          Set.of(),
          inputTables(policy, "inputs_nam", tableRefs),
          aliases(manifest),
          tableRefs);
    }

    private static Map<String, String> tableRefs(Map<String, Object> manifest) {
      Map<String, String> tableRefs = new LinkedHashMap<>();
      if (manifest.get("classification") instanceof Map<?, ?> cls
          && cls.get("bang_quyet_dinh") instanceof List<?> declared) {
        for (Object o : declared) {
          if (o instanceof Map<?, ?> m && m.get("table_key") != null) {
            tableRefs.put(
                String.valueOf(m.get("table_key")),
                m.get("legal_ref") == null ? null : String.valueOf(m.get("legal_ref")));
          }
        }
      }
      return tableRefs;
    }

    /** Bảng chỉ đọc biến đầu vào của khối {@code block}; bảng đọc kết quả tính là bảng xếp loại. */
    @SuppressWarnings("unchecked")
    private static List<String> inputTables(
        CompiledPolicy policy, String block, Map<String, String> tableRefs) {
      Set<String> inputNames =
          policy.manifest().get(block) instanceof Map<?, ?> in
              ? new HashSet<>((Set<String>) in.keySet())
              : Set.of();
      Set<String> available = policy.decisions().tableKeys();
      List<String> tables = new ArrayList<>();
      tableRefs.keySet().stream().filter(available::contains).forEach(tables::add);
      new TreeSet<>(available).stream().filter(k -> !tables.contains(k)).forEach(tables::add);
      tables.removeIf(
          k -> {
            List<String> cols = policy.decisions().inputs(k);
            return cols.isEmpty() || !inputNames.containsAll(cols);
          });
      return tables;
    }

    private static Map<String, List<String>> aliases(Map<String, Object> manifest) {
      Map<String, List<String>> aliases = new HashMap<>();
      if (manifest.get("ngu_canh") instanceof Map<?, ?> ctx) {
        ctx.forEach(
            (name, decl) -> {
              if (decl instanceof Map<?, ?> d
                  && d.get("nguon") instanceof String src
                  && src.startsWith("dmn ")) {
                aliases
                    .computeIfAbsent(src.substring(4).strip(), k -> new ArrayList<>())
                    .add(String.valueOf(name));
              }
            });
      }
      return aliases;
    }

    private static List<String> ordered(List<String> keys, Map<String, CompiledPolicy.Rule> rules) {
      return keys.stream()
          .filter(rules::containsKey)
          .sorted(
              Comparator.comparingInt((String k) -> rules.get(k).evalOrder()).thenComparing(k -> k))
          .toList();
    }

    private static int first(List<String> keys, Map<String, CompiledPolicy.Rule> rules) {
      return keys.isEmpty() ? Integer.MAX_VALUE : rules.get(keys.get(0)).evalOrder();
    }
  }

  /** Trạng thái một lần tính. */
  private static final class Run {
    final CompiledPolicy policy;
    final Plan plan;
    final Map<String, Object> outputs = new LinkedHashMap<>();
    final List<TraceStep> trace = new ArrayList<>();
    final Set<String> decided = new LinkedHashSet<>();

    Run(CompiledPolicy policy, Plan plan) {
      this.policy = policy;
      this.plan = plan;
    }

    /** Phạm vi bắt buộc (nhiệm vụ, nhóm): mọi quy tắc phải chạy được. */
    Map<String, Object> scope(List<String> keys, Map<String, Object> vars, String taskId) {
      Map<String, Object> results = new LinkedHashMap<>();
      for (String key : keys) {
        Object value = step(key, vars, taskId);
        vars.put(key, value);
        results.put(key, value);
      }
      return results;
    }

    /** Phạm vi hồ sơ: bỏ qua quy tắc còn thiếu biến tham chiếu. */
    void optional(List<String> keys, Map<String, Object> vars) {
      for (String key : keys) {
        Set<String> refs = policy.rules().get(key).program().referencedVariables();
        if (!refs.stream().allMatch(r -> vars.get(r) != null)) {
          continue;
        }
        Object value = step(key, vars, null);
        vars.put(key, value);
        outputs.put(key, value);
      }
    }

    private Object step(String key, Map<String, Object> vars, String taskId) {
      CompiledPolicy.Rule rule = policy.rules().get(key);
      Object value = policy.evaluate(key, vars);
      Map<String, Object> inputs = new TreeMap<>();
      if (taskId != null) {
        inputs.put(TASK_ID, taskId);
      }
      for (String r : rule.program().referencedVariables()) {
        if ("tham_so".equals(r)) {
          inputs.putAll(usedParams(rule.program().expression(), vars.get(r)));
        } else {
          inputs.put(r, compact(vars.get(r)));
        }
      }
      Map<String, Object> output = new TreeMap<>();
      output.put(key, value);
      add(key, rule.legalRef(), inputs, output);
      return value;
    }

    /** Chạy mọi bảng đã đủ cột đầu vào, mỗi bảng một lần. */
    void decide(Map<String, Object> vars) {
      for (String table : plan.tables) {
        if (decided.contains(table)) {
          continue;
        }
        List<String> cols = policy.decisions().inputs(table);
        Map<String, Object> in = new TreeMap<>();
        for (String c : cols) {
          Object v = vars.get(c);
          if (v == null) {
            break;
          }
          in.put(c, v);
        }
        if (in.size() < cols.size()) {
          continue;
        }
        decided.add(table);
        Map<String, Object> row = policy.decide(table, in);
        Map<String, Object> output = new TreeMap<>(row);
        Object level = row.isEmpty() ? null : row.values().iterator().next();
        row.forEach(
            (k, v) -> {
              vars.put(k, v);
              outputs.put(k, v);
            });
        if (level != null) {
          for (String alias : plan.aliases.getOrDefault(table, List.of())) {
            vars.put(alias, level);
            outputs.put(alias, level);
            output.put(alias, level);
          }
        }
        add(table, plan.tableRefs.get(table), in, output);
      }
    }

    private void add(
        String ruleCode, String legalRef, Map<String, Object> inputs, Map<String, Object> output) {
      String ref =
          legalRef == null || legalRef.length() <= 200 ? legalRef : legalRef.substring(0, 200);
      trace.add(
          new TraceStep(
              trace.size() + 1,
              ruleCode,
              ref,
              CanonicalDoc.of(inputs),
              CanonicalDoc.of(output),
              null));
    }

    private Object compact(Object value) {
      if (value instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?>) {
        return project(l, plan.resultKeys);
      }
      return value;
    }
  }
}
