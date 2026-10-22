package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelRounding;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner.Evaluation;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Tính một bộ đầu vào trên một phiên bản Policy Pack bằng cel-java và bảng quyết định (CORE-05-07):
 * biên dịch mọi định nghĩa CEL một lần khi dựng, rồi chạy theo phạm vi của gói (TL-01 "Cấu trúc
 * gói"): {@code nhiem_vu} cho từng nhiệm vụ, {@code nhom} cho từng nhóm của tham số {@code nhom},
 * các phạm vi còn lại của {@code definitions} (hoặc {@code definitions_nam} khi đầu vào có {@code
 * ket_qua_quy}) theo {@code eval_order}. Bảng quyết định chạy ngay khi đủ cột đầu vào; kết quả ghi
 * vào biến cùng tên bảng và các biến {@code ngu_canh} có {@code nguon: dmn <bảng>}. Quy tắc của
 * phiên bản ({@code policy_rule}) cùng khoá ghi đè định nghĩa của manifest; quy tắc chỉ có ở phiên
 * bản chạy cuối, bỏ qua khi thiếu biến. Đầu vào có {@code truong_hop} trả thuộc tính của trường hợp
 * đặc thù (PL 6). Mọi lần gọi CEL được ghi vết để đối chiếu với cel-js.
 */
final class PolicyEvaluator {

  /** Kết quả nhiệm vụ gom vào {@code g.ds_kpi} của phạm vi nhóm (kiểu {@code KetQuaNhomDauVao}). */
  static final String KPI_KEY = "kpi_nhiem_vu";

  /** Biến điểm của hồ sơ, dùng cho bất biến và so sánh tác động. */
  static final String SCORE_KEY = "diem";

  /** Kết quả hạ mức do thiếu điều kiện của bảng xếp loại (BR-09-10). */
  static final String LOWERED = "ha_muc";

  static final String MISSING = "dieu_kien_thieu";

  private static final String TASKS = "nhiem_vu";
  private static final String GROUPS = "nhom";
  private static final String QUARTERS = "ket_qua_quy";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Một lần gọi CEL: nhãn (định nghĩa và phần tử), lệnh gửi cel-js, kết quả cel-java thô. */
  record Trace(String label, Evaluation evaluation, Object javaResult) {}

  /** Kết quả tính: biến đã tính theo tên và vết CEL (rỗng khi không ghi vết). */
  record Outcome(Map<String, Object> outputs, List<Trace> traces) {}

  private record Def(
      String name,
      int order,
      CelCompiledProgram program,
      String returnType,
      Integer digits,
      String mode) {}

  private final CelEngine cel;
  private final Map<String, Object> params;
  private final Map<String, List<Def>> quarterScopes = new LinkedHashMap<>();
  private final Map<String, List<Def>> yearScopes = new LinkedHashMap<>();
  private final List<Def> extraRules = new ArrayList<>();
  private final Map<String, DecisionTableSpec> tables = new LinkedHashMap<>();
  private final Map<String, List<String>> aliases = new HashMap<>();
  private final Map<String, Map<String, Object>> special = new HashMap<>();
  private final String levelKey;
  private final Map<String, Object> manifest;

  /**
   * Dựng và biên dịch.
   *
   * @param tables bảng quyết định theo {@code table_key}
   * @throws BusinessException {@code POLICY-422-CEL-TYPE} khi còn biểu thức không biên dịch được
   */
  @SuppressWarnings("unchecked")
  PolicyEvaluator(
      CelEngine cel,
      ObjectMapper json,
      Map<String, Object> manifest,
      List<PolicyRule> rules,
      Map<String, DecisionTableSpec> tables) {
    this.cel = cel;
    this.manifest = manifest;
    this.params =
        manifest.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    Map<String, PolicyRule> byKey = new LinkedHashMap<>();
    rules.forEach(r -> byKey.put(r.ruleKey(), r));
    Map<String, CelValueType> types = PolicyRuleService.environment(manifest);
    rules.forEach(r -> types.put(r.ruleKey(), PolicyRuleService.resultType(r.resultType())));
    List<ProblemFieldError> errors = new ArrayList<>();
    Set<String> defined = new LinkedHashSet<>();
    compileBlock(json, "definitions", byKey, types, quarterScopes, defined, errors);
    compileBlock(json, "definitions_nam", byKey, types, yearScopes, defined, errors);
    byKey.values().stream()
        .filter(r -> !defined.contains(r.ruleKey()))
        .sorted(Comparator.comparingInt(PolicyRule::evalOrder))
        .forEach(
            r -> {
              Map<String, Object> rounding = read(json, r.roundingJson());
              compile(
                      r.ruleKey(),
                      r.evalOrder(),
                      r.expression(),
                      r.resultType(),
                      rounding,
                      types,
                      "rules." + r.ruleKey(),
                      errors)
                  .ifPresent(extraRules::add);
            });
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "POLICY-422-CEL-TYPE", "Còn biểu thức không hợp lệ nên chưa chạy được kiểm thử", errors);
    }
    orderTables(tables);
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
    if (manifest.get("truong_hop_dac_thu") instanceof List<?> cases) {
      for (Object c : cases) {
        if (c instanceof Map<?, ?> m && m.get("ma") != null) {
          special.put(String.valueOf(m.get("ma")), (Map<String, Object>) m);
        }
      }
    }
    this.levelKey =
        manifest.get("classification") instanceof Map<?, ?> cls
                && cls.get("muc_cuoi") instanceof String k
            ? k
            : "xep_loai";
  }

  /**
   * Biến mang mức xếp loại cuối cùng ({@code classification.muc_cuoi}, mặc định {@code xep_loai}).
   */
  String levelKey() {
    return levelKey;
  }

  Map<String, Object> manifest() {
    return manifest;
  }

  /** Gói có phạm vi nhiệm vụ (đầu vào {@code nhiem_vu}) để sinh dữ liệu thuộc tính. */
  boolean quarterly() {
    return quarterScopes.containsKey(TASKS);
  }

  Map<String, DecisionTableSpec> tables() {
    return tables;
  }

  /**
   * Tính một ca vàng. Đầu vào có dạng {@code {loai, tham_so, dau_vao}} (bộ ca của DL-03) hoặc là
   * chính các biến đầu vào.
   *
   * @throws RuntimeException lỗi CEL ({@code CelExecutionException}) hoặc đầu vào không dùng được
   */
  @SuppressWarnings("unchecked")
  Outcome evaluate(Map<String, Object> input, boolean trace) {
    Map<String, Object> values;
    Map<String, Object> overrides = null;
    if (input.get("dau_vao") instanceof Map<?, ?> inner) {
      values = (Map<String, Object>) inner;
      overrides = input.get("tham_so") instanceof Map<?, ?> t ? (Map<String, Object>) t : null;
    } else {
      values = input;
    }
    Run run = new Run(trace);
    if (values.get("truong_hop") != null) {
      String code = String.valueOf(values.get("truong_hop"));
      Map<String, Object> found = special.get(code);
      if (found == null) {
        throw new IllegalArgumentException("Gói không có trường hợp đặc thù " + code);
      }
      run.outputs.putAll(found);
      return new Outcome(run.outputs, run.traces);
    }
    Map<String, Object> ts = params(overrides);
    if (values.get(TASKS) instanceof List<?> tasks && quarterly()) {
      quarter(run, values, (List<Map<String, Object>>) tasks, ts);
    } else if (values.get(QUARTERS) instanceof List<?> quarters && !yearScopes.isEmpty()) {
      year(run, values, (List<Map<String, Object>>) quarters, ts);
    } else {
      Map<String, Object> vars = new HashMap<>(values);
      vars.put("tham_so", ts);
      for (List<Def> defs : quarterScopes.values()) {
        run.scope(defs, vars, "", true);
      }
      for (List<Def> defs : yearScopes.values()) {
        run.scope(defs, vars, "", true);
      }
      run.decide(vars);
      run.scope(extraRules, vars, "", true);
      run.decide(vars);
    }
    return new Outcome(run.outputs, run.traces);
  }

  private void quarter(
      Run run,
      Map<String, Object> values,
      List<Map<String, Object>> tasks,
      Map<String, Object> ts) {
    Map<String, Object> vars = new HashMap<>(values);
    vars.put("tham_so", ts);
    List<Map<String, Object>> withCode = new ArrayList<>();
    List<Map<String, Object>> taskResults = new ArrayList<>();
    for (int i = 0; i < tasks.size(); i++) {
      Map<String, Object> nv = new LinkedHashMap<>(tasks.get(i));
      nv.putIfAbsent("ma", "NV-" + i);
      withCode.add(nv);
      Map<String, Object> v = new HashMap<>(Map.of("nv", nv, "tham_so", ts));
      Map<String, Object> r = run.scope(quarterScopes.get(TASKS), v, TASKS + "[" + i + "].", false);
      Map<String, Object> result = new LinkedHashMap<>(nv);
      result.putAll(r);
      taskResults.add(result);
      r.forEach((k, x) -> run.list(k).add(x));
    }
    vars.put(TASKS, withCode);
    List<Map<String, Object>> groupResults = new ArrayList<>();
    if (quarterScopes.containsKey(GROUPS) && ts.get(GROUPS) instanceof List<?> groups) {
      int i = 0;
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
        Map<String, Object> v = new HashMap<>(Map.of("g", g, "tham_so", ts));
        Map<String, Object> r =
            run.scope(quarterScopes.get(GROUPS), v, GROUPS + "[" + i + "].", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ma", group.get("ma"));
        result.put("diem_toi_da", group.get("diem_toi_da"));
        result.putAll(r);
        groupResults.add(result);
        r.forEach((k, x) -> run.list(k).add(x));
        i++;
      }
    }
    vars.put("ket_qua_nhiem_vu", taskResults);
    vars.put("ket_qua_nhom", groupResults);
    for (Map.Entry<String, List<Def>> e : quarterScopes.entrySet()) {
      if (e.getKey().equals(TASKS) || e.getKey().equals(GROUPS)) {
        continue;
      }
      run.decide(vars);
      run.scope(e.getValue(), vars, "", true);
    }
    run.decide(vars);
    run.scope(extraRules, vars, "", true);
    run.decide(vars);
  }

  private void year(
      Run run,
      Map<String, Object> values,
      List<Map<String, Object>> quarters,
      Map<String, Object> ts) {
    Map<String, Object> vars = new HashMap<>(values);
    List<Map<String, Object>> list = new ArrayList<>();
    for (Map<String, Object> q : quarters) {
      Map<String, Object> m = new LinkedHashMap<>(q);
      // Quý không ghi co_xep_loai là quý có xếp loại (PolicyGoldenCelTest, suy luận).
      m.putIfAbsent("co_xep_loai", true);
      list.add(m);
    }
    vars.put(QUARTERS, list);
    vars.put("tham_so", ts);
    for (List<Def> defs : yearScopes.values()) {
      run.decide(vars);
      run.scope(defs, vars, "", true);
    }
    run.decide(vars);
    run.scope(extraRules, vars, "", true);
    run.decide(vars);
  }

  /**
   * Tham số mặc định ({@code params.*.mac_dinh}) ghép giá trị ghi đè theo khoá phẳng, rồi lồng theo
   * dấu chấm ({@code htxs.nguong_ty_le_vuot} thành {@code htxs.nguong_ty_le_vuot} của map).
   */
  @SuppressWarnings("unchecked")
  Map<String, Object> params(Map<String, Object> overrides) {
    Map<String, Object> flat = new LinkedHashMap<>();
    params.forEach((k, v) -> flat.put(k, v instanceof Map<?, ?> m ? m.get("mac_dinh") : v));
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

  /** Làm tròn hai bước như CORE-05-04: khử sai số nhị phân ở 10 chữ số rồi theo gói. */
  static double round(Number v, int digits, String mode) {
    return CelRounding.round(CelRounding.round(v, 10, "NUA_LEN"), digits, mode);
  }

  /** Làm tròn điểm theo {@code rounding.diem} của gói (mặc định 1 chữ số, nửa lên). */
  double roundScore(double v) {
    int digits = 1;
    String mode = "NUA_LEN";
    if (manifest.get("rounding") instanceof Map<?, ?> r
        && r.get(SCORE_KEY) instanceof Map<?, ?> d) {
      digits = d.get("digits") instanceof Number n ? n.intValue() : digits;
      mode = d.get("mode") instanceof String s ? s : mode;
    }
    return round(v, digits, mode);
  }

  @SuppressWarnings("unchecked")
  private void compileBlock(
      ObjectMapper json,
      String block,
      Map<String, PolicyRule> rules,
      Map<String, CelValueType> types,
      Map<String, List<Def>> target,
      Set<String> defined,
      List<ProblemFieldError> errors) {
    if (!(manifest.get(block) instanceof Map<?, ?> scopes)) {
      return;
    }
    Map<String, Integer> firstOrder = new HashMap<>();
    scopes.forEach(
        (scope, defs) -> {
          if (!(defs instanceof Map<?, ?> m)) {
            return;
          }
          List<Def> compiled = new ArrayList<>();
          m.forEach(
              (name, raw) -> {
                if (!(raw instanceof Map<?, ?> d)) {
                  return;
                }
                String key = String.valueOf(name);
                defined.add(key);
                PolicyRule rule = rules.get(key);
                int order = d.get("eval_order") instanceof Number n ? n.intValue() : 0;
                String expression =
                    rule != null ? rule.expression() : String.valueOf(d.get("expression"));
                String resultType =
                    rule != null ? rule.resultType() : String.valueOf(d.get("result_type"));
                Map<String, Object> rounding =
                    rule != null
                        ? read(json, rule.roundingJson())
                        : d.get("rounding") instanceof Map<?, ?> r
                            ? (Map<String, Object>) r
                            : Map.of();
                compile(
                        key,
                        order,
                        expression,
                        resultType,
                        rounding,
                        types,
                        block + "." + scope + "." + key,
                        errors)
                    .ifPresent(compiled::add);
              });
          compiled.sort(Comparator.comparingInt(Def::order));
          firstOrder.put(String.valueOf(scope), compiled.isEmpty() ? 0 : compiled.get(0).order());
          target.put(String.valueOf(scope), compiled);
        });
    List<Map.Entry<String, List<Def>>> sorted = new ArrayList<>(target.entrySet());
    sorted.sort(Comparator.comparingInt(e -> firstOrder.getOrDefault(e.getKey(), 0)));
    target.clear();
    sorted.forEach(e -> target.put(e.getKey(), e.getValue()));
  }

  private Optional<Def> compile(
      String name,
      int order,
      String expression,
      String resultType,
      Map<String, Object> rounding,
      Map<String, CelValueType> types,
      String field,
      List<ProblemFieldError> errors) {
    CelValueType returnType = PolicyRuleService.resultType(resultType);
    try {
      CelCompiledProgram program = cel.compile(expression, types, returnType);
      Integer digits = rounding.get("digits") instanceof Number n ? n.intValue() : null;
      String mode = rounding.get("mode") instanceof String s ? s : "NUA_LEN";
      return Optional.of(new Def(name, order, program, returnType.name(), digits, mode));
    } catch (CelCompilationException e) {
      String detail = e.issues().isEmpty() ? e.getMessage() : e.issues().get(0).message();
      errors.add(
          new ProblemFieldError(
              field, "Biểu thức không hợp lệ: " + detail + " tại khối " + name, "MSG-15-01"));
      return Optional.empty();
    }
  }

  private void orderTables(Map<String, DecisionTableSpec> stored) {
    if (manifest.get("classification") instanceof Map<?, ?> cls
        && cls.get("bang_quyet_dinh") instanceof List<?> declared) {
      for (Object o : declared) {
        if (o instanceof Map<?, ?> m && stored.containsKey(String.valueOf(m.get("table_key")))) {
          String key = String.valueOf(m.get("table_key"));
          tables.put(key, stored.get(key));
        }
      }
    }
    new TreeMap<>(stored).forEach(tables::putIfAbsent);
  }

  private static Map<String, Object> read(ObjectMapper json, String value) {
    try {
      return value == null || value.isBlank() ? Map.of() : json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Quy tắc làm tròn không đọc được", e);
    }
  }

  /** Trạng thái một lần tính: biến đã tính, vết CEL, bảng đã chạy. */
  private final class Run {
    final boolean trace;
    final Map<String, Object> outputs = new LinkedHashMap<>();
    final List<Trace> traces = new ArrayList<>();
    final Set<String> decided = new LinkedHashSet<>();

    Run(boolean trace) {
      this.trace = trace;
    }

    @SuppressWarnings("unchecked")
    List<Object> list(String key) {
      return (List<Object>) outputs.computeIfAbsent(key, k -> new ArrayList<>());
    }

    /**
     * Chạy các định nghĩa của một phạm vi, ghi kết quả vào {@code vars}; {@code optional} bỏ qua
     * định nghĩa thiếu biến tham chiếu.
     */
    Map<String, Object> scope(
        List<Def> defs, Map<String, Object> vars, String label, boolean optional) {
      Map<String, Object> results = new LinkedHashMap<>();
      if (defs == null) {
        return results;
      }
      for (Def d : defs) {
        Set<String> refs = d.program().referencedVariables();
        if (optional && !refs.stream().allMatch(r -> vars.get(r) != null)) {
          continue;
        }
        Object value = cel.evaluate(d.program(), vars);
        if (trace) {
          Map<String, Object> inputs = new LinkedHashMap<>();
          Map<String, String> types = new LinkedHashMap<>();
          for (String r : refs) {
            inputs.put(r, vars.get(r));
            CelValueType t = d.program().variableTypes().get(r);
            types.put(r, t == null ? CelValueType.DYN.name() : t.name());
          }
          traces.add(
              new Trace(
                  label + d.name(),
                  new Evaluation(d.program().expression(), types, d.returnType(), inputs),
                  value));
        }
        if (d.digits() != null && value instanceof Number n) {
          value = round(n, d.digits(), d.mode());
        }
        vars.put(d.name(), value);
        results.put(d.name(), value);
        if (label.isEmpty()) {
          outputs.put(d.name(), value);
        }
      }
      return results;
    }

    /** Chạy mọi bảng quyết định đã đủ cột đầu vào, lặp tới khi không chạy thêm được bảng nào. */
    void decide(Map<String, Object> vars) {
      boolean progress = true;
      while (progress) {
        progress = false;
        for (Map.Entry<String, DecisionTableSpec> e : tables.entrySet()) {
          if (decided.contains(e.getKey())) {
            continue;
          }
          Map<String, Object> in = new HashMap<>();
          boolean ready = true;
          for (DecisionTableSpec.Column c : e.getValue().columns()) {
            Object v = vars.get(c.name());
            if (v == null) {
              ready = false;
              break;
            }
            in.put(c.name(), v);
          }
          if (!ready) {
            continue;
          }
          decided.add(e.getKey());
          progress = true;
          Optional<String> level = e.getValue().evaluate(in);
          if (level.isEmpty()) {
            continue;
          }
          vars.put(e.getKey(), level.get());
          outputs.put(e.getKey(), level.get());
          if (e.getValue().columns().size() > 1
              && e.getValue().rows().stream()
                  .anyMatch(r -> DecisionTableSpec.LEVELS.get(0).equals(r.ratingLevel()))) {
            // BR-09-10, PL 6 §12: đủ điểm mức xuất sắc nhưng thiếu điều kiện thì hạ mức liền kề.
            Optional<List<String>> missing = e.getValue().missingForTop(in);
            outputs.putIfAbsent(LOWERED, missing.isPresent());
            outputs.putIfAbsent(MISSING, missing.orElse(List.of()));
          }
          for (String alias : aliases.getOrDefault(e.getKey(), List.of())) {
            vars.put(alias, level.get());
            outputs.put(alias, level.get());
          }
        }
      }
    }
  }
}
