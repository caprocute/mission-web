package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.policy.domain.FeelCondition.NumberSet;
import vn.viettel.okrkpi.policy.domain.FeelCondition.ValueSet;
import vn.viettel.okrkpi.policy.domain.FeelUnaryTests.FeelException;

/**
 * Nội dung bảng quyết định xếp loại (SCR-1502, BR-15-06): cột đầu vào, dòng (FEEL theo cột, mức xếp
 * loại, điều khoản, giải thích) và hit policy; phân tích FEEL, dòng bị che theo {@code FIRST}
 * (MSG-15-04), khoảng hở và đánh giá một bộ đầu vào (CORE-05-05).
 */
public record DecisionTableSpec(String hitPolicy, List<Column> columns, List<Row> rows) {

  /** Thứ tự mức từ cao xuống thấp (baseline mục 9.1), dùng cho hit policy {@code PRIORITY}. */
  public static final List<String> LEVELS = List.of("HTXSNV", "HTTNV", "HTNV", "KHTNV");

  public static final Set<String> HIT_POLICIES = Set.of("FIRST", "UNIQUE", "PRIORITY");

  private static final Pattern VAR = Pattern.compile("\\{([^{}]*)}");
  private static final int MAX_GRID = 50_000;

  /**
   * Cột đầu vào: tên biến ngữ cảnh và kiểu FEEL ({@code number}, {@code string}, {@code boolean}).
   */
  public record Column(String name, String label, String type) {}

  /**
   * Một dòng; {@code inputs} theo tên cột, ô thiếu là bất kỳ. {@code ratingLevel} là đầu ra của
   * dòng: mức xếp loại, hoặc giá trị tổng quát ({@code outputValue} của hợp đồng) ở bảng không xếp
   * loại.
   */
  public record Row(
      Map<String, String> inputs, String ratingLevel, String clause, String explanation) {
    public Row {
      inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
  }

  /** Lỗi FEEL ở dòng {@code row} (từ 1), cột {@code column}, vị trí {@code position} trong ô. */
  public record FeelError(int row, String column, int position, String message) {}

  /**
   * Dòng {@code row} không bao giờ được chọn vì dòng {@code shadowedBy} phía trên luôn thoả trước.
   */
  public record Shadow(int row, int shadowedBy) {}

  public DecisionTableSpec {
    Objects.requireNonNull(hitPolicy, "hitPolicy");
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
  }

  /** Phân tích mọi ô; trả các lỗi FEEL (rỗng khi bảng hợp lệ). */
  public List<FeelError> feelErrors() {
    List<FeelError> errors = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
      for (Column c : columns) {
        try {
          FeelUnaryTests.parse(rows.get(r).inputs().get(c.name()), c.type());
        } catch (FeelException e) {
          errors.add(new FeelError(r + 1, c.name(), e.position(), e.getMessage()));
        }
      }
    }
    return errors;
  }

  /** Dòng bị che hoàn toàn bởi một dòng phía trên; chỉ có nghĩa với {@code FIRST}. */
  public List<Shadow> shadowed() {
    List<Shadow> out = new ArrayList<>();
    if (!"FIRST".equals(hitPolicy)) {
      return out;
    }
    List<List<FeelCondition>> cells = conditions();
    for (int j = 1; j < cells.size(); j++) {
      for (int i = 0; i < j; i++) {
        if (coversRow(cells.get(i), cells.get(j))) {
          out.add(new Shadow(j + 1, i + 1));
          break;
        }
      }
    }
    return out;
  }

  /**
   * Một bộ đầu vào mẫu không dòng nào khớp, tìm trên lưới điểm dựng từ đầu mút các khoảng và giá
   * trị đã nêu (suy luận theo TL-01 X-PP-08); rỗng khi bảng phủ kín hoặc lưới quá lớn.
   */
  public Optional<Map<String, Object>> firstGap() {
    List<List<FeelCondition>> cells = conditions();
    List<List<Object>> samples = new ArrayList<>();
    long size = 1;
    for (int c = 0; c < columns.size(); c++) {
      List<Object> s = samples(c, cells);
      samples.add(s);
      size *= s.size();
      if (size > MAX_GRID) {
        return Optional.empty();
      }
    }
    int[] idx = new int[columns.size()];
    for (long n = 0; n < size; n++) {
      Map<String, Object> point = new LinkedHashMap<>();
      for (int c = 0; c < columns.size(); c++) {
        point.put(columns.get(c).name(), samples.get(c).get(idx[c]));
      }
      if (match(cells, point).isEmpty()) {
        return Optional.of(point);
      }
      for (int c = columns.size() - 1; c >= 0; c--) {
        if (++idx[c] < samples.get(c).size()) {
          break;
        }
        idx[c] = 0;
      }
    }
    return Optional.empty();
  }

  /**
   * Mức xếp loại cho một bộ đầu vào theo hit policy: {@code FIRST} lấy dòng đầu tiên, {@code
   * PRIORITY} lấy mức cao nhất trong các dòng khớp, {@code UNIQUE} cần đúng một dòng khớp.
   */
  public Optional<String> evaluate(Map<String, Object> input) {
    return matchedRow(input).map(Row::ratingLevel);
  }

  /** Dòng được chọn cho một bộ đầu vào theo hit policy (giống {@link #evaluate}). */
  public Optional<Row> matchedRow(Map<String, Object> input) {
    List<Integer> hits = match(conditions(), input);
    if (hits.isEmpty()) {
      return Optional.empty();
    }
    return switch (hitPolicy) {
      case "PRIORITY" ->
          hits.stream()
              .map(rows::get)
              .min(
                  (a, b) ->
                      Integer.compare(
                          LEVELS.indexOf(a.ratingLevel()), LEVELS.indexOf(b.ratingLevel())));
      case "UNIQUE" -> {
        if (hits.size() > 1) {
          throw new IllegalStateException("Nhiều dòng cùng khớp với hit policy UNIQUE");
        }
        yield Optional.of(rows.get(hits.get(0)));
      }
      default -> Optional.of(rows.get(hits.get(0)));
    };
  }

  /**
   * Điều kiện còn thiếu để đạt mức cao nhất (BR-09-10, PL 6 §12): bộ đầu vào thoả ô cột đầu (điểm)
   * của dòng {@code HTXSNV} nhưng dòng được chọn khác dòng đó và không phải {@code KHTNV} thì trả
   * tên các cột còn lại mà ô của dòng {@code HTXSNV} không thoả; ngược lại rỗng (không hạ mức).
   */
  public Optional<List<String>> missingForTop(Map<String, Object> input) {
    int top = -1;
    for (int r = 0; r < rows.size(); r++) {
      if (LEVELS.get(0).equals(rows.get(r).ratingLevel())) {
        top = r;
        break;
      }
    }
    if (top < 0 || columns.size() < 2) {
      return Optional.empty();
    }
    List<List<FeelCondition>> cells = conditions();
    List<Integer> hits = match(cells, input);
    if (hits.isEmpty()) {
      return Optional.empty();
    }
    int chosen = hits.get(0);
    List<FeelCondition> best = cells.get(top);
    if (chosen == top
        || LEVELS.get(LEVELS.size() - 1).equals(rows.get(chosen).ratingLevel())
        || !best.get(0).matches(input.get(columns.get(0).name()))) {
      return Optional.empty();
    }
    List<String> missing = new ArrayList<>();
    for (int c = 1; c < columns.size(); c++) {
      if (!best.get(c).matches(input.get(columns.get(c).name()))) {
        missing.add(columns.get(c).name());
      }
    }
    return Optional.of(missing);
  }

  /** Tên các biến {@code {bien}} trong một mẫu câu. */
  public static Set<String> templateVariables(String template) {
    Set<String> vars = new LinkedHashSet<>();
    if (template == null) {
      return vars;
    }
    Matcher m = VAR.matcher(template);
    while (m.find()) {
      vars.add(m.group(1).strip());
    }
    return vars;
  }

  private List<List<FeelCondition>> conditions() {
    List<List<FeelCondition>> out = new ArrayList<>();
    for (Row r : rows) {
      List<FeelCondition> cells = new ArrayList<>();
      for (Column c : columns) {
        cells.add(FeelUnaryTests.parse(r.inputs().get(c.name()), c.type()));
      }
      out.add(cells);
    }
    return out;
  }

  private static boolean coversRow(List<FeelCondition> above, List<FeelCondition> below) {
    for (int c = 0; c < above.size(); c++) {
      if (!above.get(c).covers(below.get(c))) {
        return false;
      }
    }
    return true;
  }

  private List<Integer> match(List<List<FeelCondition>> cells, Map<String, Object> input) {
    List<Integer> hits = new ArrayList<>();
    for (int r = 0; r < cells.size(); r++) {
      boolean all = true;
      for (int c = 0; c < columns.size() && all; c++) {
        all = cells.get(r).get(c).matches(input.get(columns.get(c).name()));
      }
      if (all) {
        hits.add(r);
      }
    }
    return hits;
  }

  private List<Object> samples(int column, List<List<FeelCondition>> cells) {
    String type = columns.get(column).type();
    if (type.equals("boolean")) {
      return List.of(true, false);
    }
    if (type.equals("string")) {
      Set<Object> values = new LinkedHashSet<>();
      for (List<FeelCondition> row : cells) {
        if (row.get(column) instanceof ValueSet v) {
          values.addAll(v.values());
        }
      }
      values.add("\u0000khac");
      return List.copyOf(values);
    }
    TreeSet<BigDecimal> bounds = new TreeSet<>();
    for (List<FeelCondition> row : cells) {
      if (row.get(column) instanceof NumberSet n) {
        bounds.addAll(n.bounds());
      }
    }
    if (bounds.isEmpty()) {
      return List.of(BigDecimal.ZERO);
    }
    List<Object> points = new ArrayList<>();
    BigDecimal prev = null;
    for (BigDecimal b : bounds) {
      points.add(prev == null ? b.subtract(BigDecimal.ONE) : prev.add(b).divide(BigDecimal.TWO));
      points.add(b);
      prev = b;
    }
    points.add(prev.add(BigDecimal.ONE));
    return points;
  }
}
