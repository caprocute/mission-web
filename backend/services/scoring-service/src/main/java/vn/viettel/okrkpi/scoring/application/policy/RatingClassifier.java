package vn.viettel.okrkpi.scoring.application.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.DecisionTable;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.scoring.domain.UnaryTest;

/**
 * Xếp loại một hồ sơ sau khi tính điểm (DV-07-05, QT-09, SCR-0903): chạy bảng quyết định xếp loại
 * của gói (bảng đọc kết quả tính, hit policy FIRST, BR-09-06..BR-09-09), rồi phạm vi CEL chứa
 * {@code classification.muc_cuoi} ({@code min_muc} giữa mức theo kết quả nhiệm vụ và mức theo tiêu
 * chí chung). Gói không khai báo {@code muc_cuoi} (luồng Nhà nước, BR-09-11) lấy mức của bảng.
 *
 * <p>Hạ mức liền kề (BR-09-10) được nhận ra từ cấu trúc bảng: dòng được chọn có chú thích {@code
 * mau_ha_muc}, dòng đầu tiên của mức liền trên khớp ở cột điểm (cột đầu) nhưng trượt ở cột khác;
 * các cột trượt là điều kiện thiếu. Câu giải thích ghép từ mẫu {@code classification.mau_cau} của
 * dòng (khi hạ mức) hoặc chú thích {@code giai_thich} của dòng, với nhãn điều kiện {@code
 * classification.dieu_kien}, giá trị thực, điều khoản và phiên bản gói. Mọi chữ hiển thị lấy từ
 * gói, không viết trong mã (YC-TK-11).
 */
public final class RatingClassifier {

  static final String DIEM = ScoreCalculator.SCORE_KEY;
  private static final String HA_MUC = "ha_muc";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

  /**
   * Kết quả xếp loại.
   *
   * @param level mức hệ thống gợi ý ({@code auto_rating_code}): mức cuối sau {@code min_muc}
   * @param tableLevel mức của bảng xếp loại theo kết quả nhiệm vụ
   * @param ruleId mã dòng bảng được chọn ({@code xep-loai-dang-dong-5})
   * @param lowered có hạ mức liền kề
   * @param missing biến cột của điều kiện thiếu, theo thứ tự cột ({@code downgrade_reasons})
   * @param explanation câu giải thích của dòng
   * @param outputs biến xếp loại đã tính (đầu ra bảng, biến {@code ngu_canh}, phạm vi mức cuối)
   * @param steps bước vết tính tiếp nối vết tính điểm
   */
  public record Rating(
      String level,
      String tableLevel,
      String ruleId,
      boolean lowered,
      List<String> missing,
      String explanation,
      Map<String, Object> outputs,
      List<TraceStep> steps) {

    public Rating {
      missing = List.copyOf(missing);
      outputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
      steps = List.copyOf(steps);
    }
  }

  /**
   * Xếp loại.
   *
   * @throws IllegalStateException gói không có bảng xếp loại chạy được trên kết quả tính, hoặc
   *     không dòng nào khớp
   */
  public Rating classify(CompiledPolicy policy, ScoreCalculator.Calculation calc) {
    Map<String, Object> manifest = policy.manifest();
    Map<String, Object> cls = map(manifest.get("classification"));
    Map<String, Object> vars = new HashMap<>(calc.context());
    Map<String, Object> outputs = new LinkedHashMap<>();
    List<TraceStep> steps = new ArrayList<>();
    int stepNo = calc.trace().size();

    String primary = null;
    DecisionTables.Hit hit = null;
    DecisionTable structure = null;
    Map<String, List<String>> aliases = aliases(manifest);
    for (String table : tables(policy, cls)) {
      List<String> cols = policy.decisions().inputs(table);
      Map<String, Object> in = new TreeMap<>();
      cols.forEach(c -> in.put(c, vars.get(c)));
      if (cols.isEmpty() || in.containsValue(null)) {
        continue;
      }
      DecisionTables.Hit h = policy.decisions().decide(table, in);
      Optional<DecisionTable> s = policy.decisions().structure(table);
      Map<String, Object> out = new TreeMap<>(h.outputs());
      Object level = h.outputs().isEmpty() ? null : h.outputs().values().iterator().next();
      if (level != null) {
        aliases.getOrDefault(table, List.of()).forEach(a -> out.put(a, level));
      }
      if (s.isPresent() && h.rule() >= 0) {
        out.put("dong_dmn", s.get().rules().get(h.rule()).id());
      }
      out.forEach(vars::put);
      outputs.putAll(out);
      if (primary == null) {
        primary = table;
        hit = h;
        structure = s.orElse(null);
      }
      steps.add(step(++stepNo, table, legalRef(cls, table), in, out));
    }
    if (primary == null || hit.outputs().isEmpty()) {
      throw new IllegalStateException("Gói quy tắc không xếp loại được hồ sơ");
    }
    String tableLevel = String.valueOf(hit.outputs().values().iterator().next());

    String finalKey = cls.get("muc_cuoi") instanceof String k ? k : null;
    for (String key : finalScope(policy, finalKey)) {
      CompiledPolicy.Rule rule = policy.rules().get(key);
      Set<String> refs = rule.program().referencedVariables();
      if (!refs.stream().allMatch(r -> vars.get(r) != null)) {
        continue;
      }
      Object value = policy.evaluate(key, vars);
      vars.put(key, value);
      outputs.put(key, value);
      Map<String, Object> in = new TreeMap<>();
      for (String r : refs) {
        if ("tham_so".equals(r)) {
          in.putAll(ScoreCalculator.usedParams(rule.program().expression(), vars.get(r)));
        } else {
          in.put(r, vars.get(r));
        }
      }
      steps.add(step(++stepNo, key, rule.legalRef(), in, Map.of(key, value)));
    }
    String level =
        finalKey != null && vars.get(finalKey) instanceof String s ? s : String.valueOf(tableLevel);

    Text text = new Text(policy, cls, vars);
    DecisionTable.Rule row = structure == null ? null : structure.rules().get(hit.rule());
    List<String> missing = List.of();
    DecisionTable.Rule upper = null;
    if (row != null && row.annotation("mau_ha_muc") != null && structure.columns().size() > 1) {
      upper = upperRow(structure, row, levels(cls));
      if (upper != null && cell(structure, upper, 0, vars)) {
        missing = failing(structure, upper, vars);
      }
    }
    boolean lowered = !missing.isEmpty();
    String explanation;
    if (lowered) {
      explanation = text.lowered(structure, row, upper, missing, level);
    } else {
      explanation = row == null ? null : text.plain(row, level);
    }
    if (explanation != null && !level.equals(tableLevel)) {
      explanation = text.limited(explanation, level, cls);
    }
    if (explanation != null && explanation.length() > 1000) {
      explanation = explanation.substring(0, 1000);
    }
    TraceStep first = steps.get(0);
    steps.set(
        0,
        new TraceStep(
            first.stepNo(),
            first.ruleCode(),
            first.legalRef(),
            first.inputs(),
            first.output(),
            explanation));
    return new Rating(
        level,
        tableLevel,
        row == null ? null : row.id(),
        lowered,
        missing,
        explanation,
        outputs,
        steps);
  }

  /** Bảng đọc kết quả tính, theo thứ tự khai báo trong {@code classification.bang_quyet_dinh}. */
  private static List<String> tables(CompiledPolicy policy, Map<String, Object> cls) {
    Set<String> inputNames = map(policy.manifest().get("inputs")).keySet();
    Set<String> available = policy.decisions().tableKeys();
    List<String> ordered = new ArrayList<>();
    if (cls.get("bang_quyet_dinh") instanceof List<?> declared) {
      for (Object o : declared) {
        if (map(o).get("table_key") instanceof String k && available.contains(k)) {
          ordered.add(k);
        }
      }
    }
    available.stream().sorted().filter(k -> !ordered.contains(k)).forEach(ordered::add);
    ordered.removeIf(k -> inputNames.containsAll(policy.decisions().inputs(k)));
    return ordered;
  }

  private static String legalRef(Map<String, Object> cls, String table) {
    if (cls.get("bang_quyet_dinh") instanceof List<?> declared) {
      for (Object o : declared) {
        Map<String, Object> m = map(o);
        if (table.equals(m.get("table_key")) && m.get("legal_ref") != null) {
          return String.valueOf(m.get("legal_ref"));
        }
      }
    }
    return null;
  }

  private static Map<String, List<String>> aliases(Map<String, Object> manifest) {
    Map<String, List<String>> aliases = new HashMap<>();
    map(manifest.get("ngu_canh"))
        .forEach(
            (name, decl) -> {
              if (map(decl).get("nguon") instanceof String src && src.startsWith("dmn ")) {
                aliases.computeIfAbsent(src.substring(4).strip(), k -> new ArrayList<>()).add(name);
              }
            });
    return aliases;
  }

  /** Quy tắc của phạm vi chứa {@code muc_cuoi}, theo {@code eval_order}. */
  private static List<String> finalScope(CompiledPolicy policy, String finalKey) {
    if (finalKey == null) {
      return List.of();
    }
    for (Object defs : map(policy.manifest().get("definitions")).values()) {
      Set<String> keys = map(defs).keySet();
      if (keys.contains(finalKey)) {
        return keys.stream()
            .filter(policy.rules()::containsKey)
            .sorted(
                Comparator.comparingInt((String k) -> policy.rules().get(k).evalOrder())
                    .thenComparing(k -> k))
            .toList();
      }
    }
    return List.of();
  }

  private static List<String> levels(Map<String, Object> cls) {
    List<String> levels = new ArrayList<>();
    if (cls.get("thu_tu") instanceof List<?> l) {
      l.forEach(o -> levels.add(String.valueOf(o)));
    }
    return levels;
  }

  /** Dòng đầu tiên của mức liền trên mức của dòng được chọn. */
  private static DecisionTable.Rule upperRow(
      DecisionTable table, DecisionTable.Rule row, List<String> levels) {
    int idx = levels.indexOf(row.outputValue());
    if (idx <= 0) {
      return null;
    }
    String above = levels.get(idx - 1);
    return table.rules().stream()
        .filter(r -> above.equals(r.outputValue()))
        .findFirst()
        .orElse(null);
  }

  private static boolean cell(
      DecisionTable table, DecisionTable.Rule row, int col, Map<String, Object> vars) {
    return UnaryTest.matches(row.cells().get(col), vars.get(table.columns().get(col).name()));
  }

  private static List<String> failing(
      DecisionTable table, DecisionTable.Rule row, Map<String, Object> vars) {
    List<String> out = new ArrayList<>();
    for (int i = 1; i < table.columns().size(); i++) {
      if (!cell(table, row, i, vars)) {
        out.add(table.columns().get(i).name());
      }
    }
    return out;
  }

  private static TraceStep step(
      int no, String code, String legalRef, Map<String, Object> in, Map<String, Object> out) {
    String ref =
        legalRef == null || legalRef.length() <= 200 ? legalRef : legalRef.substring(0, 200);
    return new TraceStep(
        no, code, ref, CanonicalDoc.of(in), CanonicalDoc.of(new TreeMap<>(out)), null);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  /** Ghép câu giải thích từ chữ của gói. */
  private static final class Text {
    final CompiledPolicy policy;
    final Map<String, Object> cls;
    final Map<String, Object> vars;

    Text(CompiledPolicy policy, Map<String, Object> cls, Map<String, Object> vars) {
      this.policy = policy;
      this.cls = cls;
      this.vars = vars;
    }

    String plain(DecisionTable.Rule row, String level) {
      String template = row.annotation("giai_thich");
      if (template == null) {
        return null;
      }
      Map<String, String> values = common(level);
      values.put("dieu_khoan", nz(row.annotation("dieu_khoan")));
      return fill(template, values);
    }

    String lowered(
        DecisionTable table,
        DecisionTable.Rule row,
        DecisionTable.Rule upper,
        List<String> missing,
        String level) {
      Map<String, Object> templates = map(cls.get("mau_cau"));
      Object template = templates.get(row.annotation("mau_ha_muc"));
      if (!(template instanceof String)) {
        template = templates.get(HA_MUC);
      }
      if (!(template instanceof String t)) {
        return plain(row, level);
      }
      Map<String, String> values = common(level);
      values.put("muc_tren", label(upper.outputValue()));
      BigDecimal threshold = UnaryTest.threshold(upper.cells().get(0));
      values.put("nguong", threshold == null ? "" : number(threshold));
      Set<String> names = new LinkedHashSet<>();
      Set<String> actual = new LinkedHashSet<>();
      Set<String> refs = new LinkedHashSet<>();
      for (String column : missing) {
        Map<String, Object> condition = condition(column);
        String name = condition.get("nhan") instanceof String n ? n : columnLabel(table, column);
        names.add(withThreshold(name, column));
        actual.add(actual(column));
        if (condition.get("legal_ref") instanceof String ref) {
          refs.add(ref);
        }
      }
      if (refs.isEmpty() && upper.annotation("dieu_khoan") != null) {
        refs.add(upper.annotation("dieu_khoan"));
      }
      values.put("dieu_kien_thieu", String.join(" và ", names));
      values.put("gia_tri", String.join(", ", actual));
      values.put("dieu_khoan", String.join("; ", refs));
      return fill(t, values);
    }

    /**
     * Mức cuối thấp hơn mức của bảng mà không do quy tắc người đứng đầu: giới hạn theo mức công
     * chức với người giữ chức vụ (BR-09-11, suy luận từ {@code min_muc} của {@code muc_cuoi}), nối
     * câu {@code mau_cau.gioi_han_luong_nha_nuoc}. Câu của quy tắc người đứng đầu thuộc CORE-04-07.
     */
    String limited(String explanation, String level, Map<String, Object> cls) {
      Object head = cls.get("nguoi_dung_dau") instanceof String k ? vars.get(k) : null;
      if (Boolean.TRUE.equals(head)
          || !(map(cls.get("mau_cau")).get("gioi_han_luong_nha_nuoc") instanceof String t)) {
        return explanation;
      }
      Map<String, String> values = common(level);
      values.put("gia_tri", label(level));
      String extra = fill(t, values);
      // Hai câu cùng kết bằng câu phiên bản gói thì chỉ giữ câu ở cuối.
      int last = extra.lastIndexOf(". ", extra.length() - 2);
      String tail = last < 0 ? null : extra.substring(last + 2);
      String kept =
          tail != null && explanation.endsWith(" " + tail)
              ? explanation.substring(0, explanation.length() - tail.length() - 1)
              : explanation;
      return kept + " " + extra;
    }

    private Map<String, String> common(String level) {
      Map<String, String> values = new HashMap<>();
      values.put("muc", label(level));
      values.put("diem", vars.get(DIEM) instanceof Number n ? number(n) : "");
      values.put("phien_ban", policy.pinned().version());
      return values;
    }

    /** Điều kiện theo tên cột: khoá trùng tên, hoặc khoá dài nhất là tiền tố của tên cột. */
    private Map<String, Object> condition(String column) {
      Map<String, Object> conditions = map(cls.get("dieu_kien"));
      if (conditions.containsKey(column)) {
        return map(conditions.get(column));
      }
      return conditions.keySet().stream()
          .filter(column::startsWith)
          .max(Comparator.comparingInt(String::length))
          .map(k -> map(conditions.get(k)))
          .orElse(Map.of());
    }

    private static String columnLabel(DecisionTable table, String column) {
      return table.columns().stream()
          .filter(c -> c.name().equals(column))
          .map(c -> c.label() == null ? column : c.label().toLowerCase(java.util.Locale.ROOT))
          .findFirst()
          .orElse(column);
    }

    /** Thay {@code {nguong}} trong nhãn bằng tham số số đầu tiên mà quy tắc CEL của cột đọc. */
    private String withThreshold(String name, String column) {
      if (!name.contains("{nguong}")) {
        return name;
      }
      CompiledPolicy.Rule rule = policy.rules().get(column);
      String value = "";
      if (rule != null) {
        for (Map.Entry<String, Object> e :
            ScoreCalculator.usedParams(rule.program().expression(), vars.get("tham_so"))
                .entrySet()) {
          if (e.getValue() instanceof Number n) {
            value = e.getKey().contains("ty_le") ? percent(n) : number(n);
            break;
          }
        }
      }
      return name.replace("{nguong}", value);
    }

    /** Giá trị thực: biến số mà quy tắc của cột đọc (như {@code ty_le_vuot}), hoặc giá trị cột. */
    private String actual(String column) {
      CompiledPolicy.Rule rule = policy.rules().get(column);
      if (rule != null) {
        for (String r : new java.util.TreeSet<>(rule.program().referencedVariables())) {
          if (!"tham_so".equals(r) && vars.get(r) instanceof Number n) {
            return display(r, n);
          }
        }
      }
      return display(column, vars.get(column));
    }

    private String display(String name, Object value) {
      if (value instanceof Boolean b) {
        return b ? "có" : "không";
      }
      if (value instanceof Number n) {
        return name.contains("ty_le") ? percent(n) : number(n);
      }
      Map<String, Object> labels = map(map(cls.get("nhan_hien_thi")).get(name));
      if (labels.get(String.valueOf(value)) instanceof String s) {
        return s;
      }
      return label(String.valueOf(value));
    }

    private String label(String level) {
      Map<String, Object> labels = map(map(cls.get("nhan_hien_thi")).get("muc_xep_loai"));
      return labels.get(level) instanceof String s ? s : level;
    }

    private static String fill(String template, Map<String, String> values) {
      Matcher m = PLACEHOLDER.matcher(template);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String v = values.get(m.group(1));
        m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
      }
      m.appendTail(out);
      return out.toString();
    }

    private static String nz(String s) {
      return s == null ? "" : s;
    }
  }

  /** Số kiểu Việt Nam: tối đa hai chữ số thập phân, dấu phẩy thập phân. */
  static String number(Number n) {
    BigDecimal d =
        new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    if (d.scale() < 0) {
      d = d.setScale(0);
    }
    return d.toPlainString().replace('.', ',');
  }

  static String percent(Number n) {
    return number(new BigDecimal(n.toString()).multiply(BigDecimal.valueOf(100))) + "%";
  }
}
