package vn.viettel.okrkpi.policy.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kiểm nội dung ca kiểm thử vàng (CORE-05-06): đầu vào chỉ dùng biến gói khai báo, kết quả mong đợi
 * có ít nhất một đầu ra của gói, và không mang dáng dữ liệu cá nhân thật (SEC-52, chỉ dữ liệu giả
 * lập, suy luận cách nhận diện).
 */
public final class GoldenCaseRules {

  /** Khoá bao của đầu vào theo bộ ca của gói ({@code loai}, {@code tham_so}, {@code dau_vao}). */
  public static final Set<String> ENVELOPE = Set.of("loai", "tham_so", "dau_vao");

  /** Đầu ra xếp loại luôn có của gói. */
  public static final Set<String> CLASSIFICATION_OUTPUTS =
      Set.of("diem", "xep_loai", "muc_cuoi", "ha_muc", "dieu_kien_thieu", "dong_dmn");

  private static final Pattern PERSONAL =
      Pattern.compile("^(\\d{9}|\\d{12})$|^0\\d{9,10}$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private GoldenCaseRules() {}

  /** Đầu vào: phần {@code dau_vao} (hoặc cả đầu vào) chỉ gồm biến trong {@code declared}. */
  public static List<PolicyManifestRules.Problem> checkInput(
      Map<String, Object> input, Set<String> declared) {
    List<PolicyManifestRules.Problem> problems = new ArrayList<>();
    if (input == null || input.isEmpty()) {
      problems.add(new PolicyManifestRules.Problem("input", "Ca cần dữ liệu đầu vào"));
      return problems;
    }
    Map<?, ?> values = input;
    String prefix = "input.";
    if (input.get("dau_vao") instanceof Map<?, ?> inner) {
      for (String k : input.keySet()) {
        if (!ENVELOPE.contains(k)) {
          problems.add(new PolicyManifestRules.Problem("input." + k, "Khoá không thuộc bộ ca"));
        }
      }
      values = inner;
      prefix = "input.dau_vao.";
    }
    for (Object k : values.keySet()) {
      if (!declared.contains(String.valueOf(k))) {
        problems.add(
            new PolicyManifestRules.Problem(
                prefix + k, "Biến " + k + " không có trong khai báo đầu vào của gói"));
      }
    }
    personal(input, "input", problems);
    return problems;
  }

  /** Kết quả mong đợi: không rỗng và có ít nhất một đầu ra của gói. */
  public static List<PolicyManifestRules.Problem> checkExpected(
      Map<String, Object> expected, Set<String> outputs) {
    List<PolicyManifestRules.Problem> problems = new ArrayList<>();
    if (expected == null
        || expected.keySet().stream()
            .noneMatch(k -> outputs.contains(k) || CLASSIFICATION_OUTPUTS.contains(k))) {
      problems.add(
          new PolicyManifestRules.Problem(
              "expected",
              "Kết quả mong đợi cần ít nhất điểm, mức xếp loại hoặc một kết quả của gói"));
    } else {
      personal(expected, "expected", problems);
    }
    return problems;
  }

  private static void personal(Object value, String at, List<PolicyManifestRules.Problem> out) {
    if (value instanceof Map<?, ?> m) {
      m.forEach((k, v) -> personal(v, at + "." + k, out));
    } else if (value instanceof Collection<?> c) {
      int i = 0;
      for (Object v : c) {
        personal(v, at + "[" + i++ + "]", out);
      }
    } else if (value instanceof String s && PERSONAL.matcher(s.strip()).find()) {
      out.add(
          new PolicyManifestRules.Problem(
              at, "Giá trị có dạng dữ liệu cá nhân; ca kiểm thử chỉ dùng dữ liệu giả lập"));
    }
  }
}
