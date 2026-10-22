package vn.viettel.okrkpi.okr.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * So nội dung người soạn lưu với đầu ra của gợi ý AI (CORE-01-13, BR-17-08): trả các trường giữ
 * nguyên và các trường đã sửa. Chỉ so các trường mà gợi ý có; chữ so sau khi chuẩn hoá Unicode NFC,
 * bỏ khoảng trắng thừa; số so theo giá trị. Đầu ra gợi ý mục tiêu ({@code MUC_TIEU}) có dạng {@code
 * {objective: {title, keyResults: [{title, unit, baselineValue, targetValue}]}}}; gợi ý khác có văn
 * bản ở {@code text}, coi như tên đề xuất (suy luận theo đầu ra của ai-gateway DV-14-04).
 */
public final class AiContentDiff {

  public static final String AI_ACCEPTED = "AI_ACCEPTED";
  public static final String AI_EDITED = "AI_EDITED";

  /** Kết quả so: trường giữ nguyên, trường đã sửa. */
  public record Result(List<String> kept, List<String> edited) {
    public String contentSource() {
      return edited.isEmpty() ? AI_ACCEPTED : AI_EDITED;
    }
  }

  private AiContentDiff() {}

  /** So mục tiêu: chỉ có tên. */
  public static Result objective(Map<String, Object> output, String title) {
    Map<String, Object> proposed = new LinkedHashMap<>();
    Object objective = output == null ? null : output.get("objective");
    if (objective instanceof Map<?, ?> o && o.get("title") != null) {
      proposed.put("title", o.get("title"));
    } else if (output != null && output.get("text") != null) {
      proposed.put("title", output.get("text"));
    }
    Map<String, Object> saved = new LinkedHashMap<>();
    saved.put("title", title);
    return compare(proposed, saved);
  }

  /** So KR với KR đề xuất gần nhất theo tên trong gợi ý. */
  public static Result keyResult(Map<String, Object> output, Map<String, Object> saved) {
    return compare(closest(candidates(output), String.valueOf(saved.get("title"))), saved);
  }

  static List<Map<String, Object>> candidates(Map<String, Object> output) {
    List<Map<String, Object>> list = new ArrayList<>();
    if (output == null) {
      return list;
    }
    if (output.get("objective") instanceof Map<?, ?> o
        && o.get("keyResults") instanceof List<?> krs) {
      for (Object k : krs) {
        if (k instanceof Map<?, ?> m) {
          Map<String, Object> c = new LinkedHashMap<>();
          m.forEach((key, value) -> c.put(String.valueOf(key), value));
          list.add(c);
        }
      }
    }
    if (list.isEmpty() && output.get("text") != null) {
      list.add(Map.of("title", output.get("text")));
    }
    return list;
  }

  static Map<String, Object> closest(List<Map<String, Object>> candidates, String title) {
    Map<String, Object> best = Map.of();
    double score = -1;
    for (Map<String, Object> c : candidates) {
      double s = similarity(String.valueOf(c.get("title")), title);
      if (s > score) {
        score = s;
        best = c;
      }
    }
    return best;
  }

  /** Độ giống Jaccard theo từ sau chuẩn hoá. */
  static double similarity(String a, String b) {
    Set<String> x = words(a);
    Set<String> y = words(b);
    if (x.isEmpty() && y.isEmpty()) {
      return 1;
    }
    Set<String> both = new HashSet<>(x);
    both.retainAll(y);
    Set<String> any = new HashSet<>(x);
    any.addAll(y);
    return (double) both.size() / any.size();
  }

  private static Set<String> words(String s) {
    Set<String> out = new HashSet<>();
    for (String w : normalize(s).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (!w.isEmpty()) {
        out.add(w);
      }
    }
    return out;
  }

  private static Result compare(Map<String, Object> proposed, Map<String, Object> saved) {
    List<String> kept = new ArrayList<>();
    List<String> edited = new ArrayList<>();
    // Theo thứ tự trường của nội dung lưu cho kết quả ổn định.
    for (Map.Entry<String, Object> e : saved.entrySet()) {
      if (proposed.containsKey(e.getKey())) {
        (same(proposed.get(e.getKey()), e.getValue()) ? kept : edited).add(e.getKey());
      }
    }
    if (proposed.isEmpty()) {
      edited.addAll(saved.keySet());
    }
    return new Result(List.copyOf(kept), List.copyOf(edited));
  }

  static boolean same(Object proposed, Object saved) {
    if (proposed == null || saved == null) {
      return proposed == null && saved == null;
    }
    BigDecimal a = number(proposed);
    BigDecimal b = number(saved);
    if (a != null && b != null) {
      return a.compareTo(b) == 0;
    }
    return Objects.equals(normalize(String.valueOf(proposed)), normalize(String.valueOf(saved)));
  }

  private static BigDecimal number(Object v) {
    if (v instanceof BigDecimal d) {
      return d;
    }
    if (v instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return null;
  }

  private static String normalize(String s) {
    return s == null
        ? ""
        : Normalizer.normalize(s, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
  }
}
