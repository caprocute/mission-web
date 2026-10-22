package vn.viettel.okrkpi.policy.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.ResultComparison;

/**
 * Kiểm thử thuộc tính của một phiên bản (CORE-05-07, TL-01 "Bộ ca mẫu vàng"): sinh {@code
 * propertyDatasets} bộ đầu vào ngẫu nhiên theo khai báo {@code inputs} và {@code types} của gói
 * (hạt giống cố định theo lần chạy để tái lập), chạy trên cel-java và bảng quyết định, kiểm bốn bất
 * biến và ba ca biên. Sinh dữ liệu ở máy chủ trong tác vụ {@code TEST_RUN} nên không dùng jqwik
 * (khung kiểm thử của JUnit, không chạy trong ứng dụng); jqwik vẫn dùng được cho kiểm thử của mã
 * nguồn.
 */
final class PolicyPropertyCheck {

  static final String INV_SCORE = "0 <= diem && diem <= 100";
  static final String INV_TIMELINESS = "C >= 0";
  static final String INV_MONOTONE = "Tăng số lượng được duyệt không làm giảm mức xếp loại";
  static final String INV_OBJECTIVES =
      "Tỷ lệ hoàn thành mục tiêu dưới 1 không xếp Hoàn thành xuất sắc nhiệm vụ";
  static final String EDGE_ROUNDING = "Điểm 59,995 và 89,995 làm tròn trước khi xếp loại";
  static final String EDGE_ZERO_PLAN = "Kế hoạch bằng 0 vẫn tính được điểm";

  /** Biến tiến độ của từng nhiệm vụ (C trong công thức KPI của PL 3). */
  static final String TIMELINESS_KEY = "kpi_C";

  static final String APPROVED_KEY = "so_luong_duyet";
  static final String PLAN_KEY = "ke_hoach";
  static final String OBJECTIVES_KEY = "ty_le_O";

  private static final int MAX_SAMPLES = 5;
  private static final int MAX_LIST = 6;

  /** Kết quả; {@code samples} là vài vi phạm đầu tiên để người soạn đọc. */
  record Report(
      int datasets,
      List<String> invariants,
      int violations,
      List<String> samples,
      Map<String, Boolean> edges,
      long seed) {

    Map<String, Object> json() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("datasets", datasets);
      m.put("invariants", invariants);
      m.put("violations", violations);
      m.put("samples", samples);
      m.put("edges", edges);
      m.put("seed", seed);
      return m;
    }
  }

  private final PolicyEvaluator evaluator;
  private final Map<String, Object> inputs;
  private final Map<String, Object> types;
  private final SplittableRandom random;
  private final long seed;
  private final List<String> samples = new ArrayList<>();
  private int violations;

  @SuppressWarnings("unchecked")
  PolicyPropertyCheck(PolicyEvaluator evaluator, long seed) {
    this.evaluator = evaluator;
    Map<String, Object> manifest = evaluator.manifest();
    this.inputs =
        manifest.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    this.types = manifest.get("types") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    this.seed = seed;
    this.random = new SplittableRandom(seed);
  }

  Report run(int datasets) {
    List<String> invariants = new ArrayList<>();
    boolean any = false;
    boolean score = false;
    boolean timeliness = false;
    boolean monotone = false;
    boolean objectives = false;
    Map<String, Object> sample = null;
    Map<String, Object> sampleOut = null;
    for (int i = 0; i < datasets && !inputs.isEmpty(); i++) {
      Map<String, Object> data = dataset();
      Map<String, Object> out;
      try {
        out = evaluator.evaluate(data, false).outputs();
      } catch (RuntimeException e) {
        violation("Bộ " + (i + 1) + ": không tính được (" + e.getMessage() + ")");
        continue;
      }
      any = true;
      if (sample == null) {
        sample = data;
        sampleOut = out;
      }
      if (out.get(PolicyEvaluator.SCORE_KEY) instanceof Number d) {
        score = true;
        if (d.doubleValue() < 0 || d.doubleValue() > 100) {
          violation("Bộ " + (i + 1) + ": điểm " + d + " ngoài [0; 100]");
        }
      }
      if (out.get(TIMELINESS_KEY) instanceof List<?> cs) {
        timeliness = true;
        for (Object c : cs) {
          if (c instanceof Number n && n.doubleValue() < 0) {
            violation("Bộ " + (i + 1) + ": C = " + n + " âm");
          }
        }
      }
      Object level = out.get(evaluator.levelKey());
      if (level != null && data.get(OBJECTIVES_KEY) instanceof Number o) {
        objectives = true;
        if (o.doubleValue() < 1 && "HTXSNV".equals(level)) {
          violation("Bộ " + (i + 1) + ": tỷ lệ hoàn thành mục tiêu " + o + " vẫn xếp xuất sắc");
        }
      }
      if (level != null && evaluator.quarterly()) {
        monotone |= checkMonotone(i, data, level);
      }
    }
    if (any) {
      if (score) {
        invariants.add(INV_SCORE);
      }
      if (timeliness) {
        invariants.add(INV_TIMELINESS);
      }
      if (monotone) {
        invariants.add(INV_MONOTONE);
      }
      if (objectives) {
        invariants.add(INV_OBJECTIVES);
      }
    }
    Map<String, Boolean> edges = new LinkedHashMap<>();
    if (sample != null) {
      edges.put(EDGE_ROUNDING, roundingEdge(sample, sampleOut));
      if (evaluator.quarterly()) {
        edges.put(EDGE_ZERO_PLAN, zeroPlanEdge(sample));
      }
    }
    edges.forEach(
        (name, ok) -> {
          if (!ok) {
            violation("Ca biên chưa đạt: " + name);
          }
        });
    return new Report(datasets, invariants, violations, List.copyOf(samples), edges, seed);
  }

  @SuppressWarnings("unchecked")
  private boolean checkMonotone(int i, Map<String, Object> data, Object level) {
    if (!(data.get("nhiem_vu") instanceof List<?> tasks) || tasks.isEmpty()) {
      return false;
    }
    Map<String, Object> more = new HashMap<>(data);
    List<Map<String, Object>> copy = new ArrayList<>();
    for (Object t : tasks) {
      copy.add(new LinkedHashMap<>((Map<String, Object>) t));
    }
    Map<String, Object> picked = copy.get(random.nextInt(copy.size()));
    if (!(picked.get(APPROVED_KEY) instanceof Number n)) {
      return false;
    }
    picked.put(APPROVED_KEY, n.intValue() + 1 + random.nextInt(5));
    more.put("nhiem_vu", copy);
    try {
      Object after = evaluator.evaluate(more, false).outputs().get(evaluator.levelKey());
      if (after != null && ResultComparison.higherThan(level, after)) {
        violation(
            "Bộ "
                + (i + 1)
                + ": tăng số lượng được duyệt làm mức giảm từ "
                + level
                + " xuống "
                + after);
      }
    } catch (RuntimeException e) {
      violation("Bộ " + (i + 1) + ": không tính được khi tăng số lượng (" + e.getMessage() + ")");
    }
    return true;
  }

  /**
   * Điểm biên làm tròn lên ngưỡng (59,995 thành 60,0; 89,995 thành 90,0) và bảng quyết định có cột
   * điểm cho cùng mức với điểm đúng ngưỡng.
   */
  private boolean roundingEdge(Map<String, Object> data, Map<String, Object> out) {
    boolean ok = evaluator.roundScore(59.995) == 60.0 && evaluator.roundScore(89.995) == 90.0;
    Map<String, Object> base = new HashMap<>(data);
    base.putAll(out);
    for (DecisionTableSpec t : evaluator.tables().values()) {
      if (t.columns().stream().noneMatch(c -> c.name().equals(PolicyEvaluator.SCORE_KEY))) {
        continue;
      }
      if (t.columns().stream().anyMatch(c -> base.get(c.name()) == null)) {
        continue;
      }
      for (double[] edge : new double[][] {{59.995, 60.0}, {89.995, 90.0}}) {
        Map<String, Object> a = new HashMap<>(base);
        a.put(PolicyEvaluator.SCORE_KEY, evaluator.roundScore(edge[0]));
        Map<String, Object> b = new HashMap<>(base);
        b.put(PolicyEvaluator.SCORE_KEY, edge[1]);
        ok &= t.evaluate(a).equals(t.evaluate(b));
      }
    }
    return ok;
  }

  @SuppressWarnings("unchecked")
  private boolean zeroPlanEdge(Map<String, Object> data) {
    if (!(data.get("nhiem_vu") instanceof List<?> tasks)) {
      return true;
    }
    Map<String, Object> zero = new HashMap<>(data);
    List<Map<String, Object>> copy = new ArrayList<>();
    for (Object t : tasks) {
      Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) t);
      m.put(PLAN_KEY, 0);
      copy.add(m);
    }
    zero.put("nhiem_vu", copy);
    try {
      Object d = evaluator.evaluate(zero, false).outputs().get(PolicyEvaluator.SCORE_KEY);
      return !(d instanceof Number n) || (n.doubleValue() >= 0 && n.doubleValue() <= 100);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void violation(String message) {
    violations++;
    if (samples.size() < MAX_SAMPLES) {
      samples.add(message);
    }
  }

  /** Một bộ đầu vào theo khối {@code inputs} của gói. */
  Map<String, Object> dataset() {
    Map<String, Object> data = new LinkedHashMap<>();
    inputs.forEach((name, decl) -> data.put(name, value(decl, 0)));
    return data;
  }

  private Object value(Object decl, int depth) {
    Map<?, ?> d = decl instanceof Map<?, ?> m ? m : Map.of("type", String.valueOf(decl));
    String type = String.valueOf(d.get("type"));
    if (d.get("enum") instanceof List<?> options && !options.isEmpty()) {
      return options.get(random.nextInt(options.size()));
    }
    if (d.get("enum_scale") instanceof List<?> scale && !scale.isEmpty()) {
      return scale.get(random.nextInt(scale.size()));
    }
    if (type.startsWith("list<") && type.endsWith(">")) {
      String item = type.substring(5, type.length() - 1);
      int size = depth > 2 ? 0 : 1 + random.nextInt(MAX_LIST);
      List<Object> list = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        list.add(value(Map.of("type", item), depth + 1));
      }
      return list;
    }
    switch (type) {
      case "double" -> {
        double min = number(d.get("min"), 0);
        double max = number(d.get("max"), Math.max(min, 1));
        int edge = random.nextInt(10);
        if (edge == 0) {
          return min;
        }
        if (edge == 1) {
          return max;
        }
        return min + random.nextDouble() * (max - min);
      }
      case "int" -> {
        int min = (int) number(d.get("min"), 0);
        int max = (int) number(d.get("max"), min + 20);
        return min + random.nextInt(Math.max(1, max - min + 1));
      }
      case "bool" -> {
        return random.nextBoolean();
      }
      case "string" -> {
        return "";
      }
      default -> {
        if (types.get(type) instanceof Map<?, ?> fields && depth <= 2) {
          Map<String, Object> obj = new LinkedHashMap<>();
          fields.forEach((k, v) -> obj.put(String.valueOf(k), value(v, depth + 1)));
          return obj;
        }
        return "";
      }
    }
  }

  private static double number(Object v, double fallback) {
    return v instanceof Number n ? n.doubleValue() : fallback;
  }
}
