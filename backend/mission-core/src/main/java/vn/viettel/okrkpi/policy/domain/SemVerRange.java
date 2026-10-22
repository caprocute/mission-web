package vn.viettel.okrkpi.policy.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dải phiên bản dạng npm rút gọn dùng trong {@code requires.policyPack} của UI Pack (TL-01 ví dụ
 * {@code khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0}): các điều kiện cách nhau bằng khoảng trắng, mỗi
 * điều kiện là {@code >=, >, <=, <, =, ^, ~} hoặc không dấu, phiên bản có thể thiếu phần sau.
 */
public final class SemVerRange {

  private static final Pattern TERM =
      Pattern.compile("^(>=|<=|>|<|=|\\^|~)?([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?$");

  private final List<Predicate<SemVer>> terms;

  private SemVerRange(List<Predicate<SemVer>> terms) {
    this.terms = terms;
  }

  /** Đọc dải; sai dạng trả rỗng. */
  public static Optional<SemVerRange> parse(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    List<Predicate<SemVer>> terms = new ArrayList<>();
    for (String token : text.strip().split("\\s+")) {
      Matcher m = TERM.matcher(token);
      if (!m.matches()) {
        return Optional.empty();
      }
      String op = m.group(1) == null ? "=" : m.group(1);
      int major = Integer.parseInt(m.group(2));
      int minor = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
      int patch = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
      SemVer v = new SemVer(major, minor, patch);
      terms.add(
          switch (op) {
            case ">=" -> s -> s.compareTo(v) >= 0;
            case ">" -> s -> s.compareTo(v) > 0;
            case "<=" -> s -> s.compareTo(v) <= 0;
            case "<" -> s -> s.compareTo(v) < 0;
            case "^" -> {
              SemVer upper = major > 0 ? new SemVer(major + 1, 0, 0) : new SemVer(0, minor + 1, 0);
              yield s -> s.compareTo(v) >= 0 && s.compareTo(upper) < 0;
            }
            case "~" -> {
              SemVer upper = new SemVer(major, minor + 1, 0);
              yield s -> s.compareTo(v) >= 0 && s.compareTo(upper) < 0;
            }
            default -> s -> s.equals(v);
          });
    }
    return Optional.of(new SemVerRange(List.copyOf(terms)));
  }

  public boolean contains(SemVer version) {
    return terms.stream().allMatch(t -> t.test(version));
  }
}
