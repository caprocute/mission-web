package vn.viettel.okrkpi.policy.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dải phiên bản Policy Pack mà UI Pack khai ({@code requires.policyPack}, ví dụ {@code
 * khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0}, {@code ^2.1}, {@code ~1.2.0}, {@code 1.3.0}, {@code *}).
 * Các vế cách nhau bằng khoảng trắng là phép "và"; phiên bản thiếu phần được bù 0.
 *
 * @param code mã gói đứng trước {@code @}, {@code null} khi dải không ghi mã
 */
public record SemVerRange(String code, Predicate<SemVer> test) {

  private static final Pattern TERM =
      Pattern.compile("^(\\^|~|>=|<=|>|<|=)?v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");

  public boolean includes(SemVer v) {
    return test.test(v);
  }

  /** Rỗng khi dải viết sai dạng. */
  public static Optional<SemVerRange> parse(String text) {
    if (text == null || text.isBlank()) {
      return Optional.of(new SemVerRange(null, v -> true));
    }
    String body = text.strip();
    String code = null;
    int at = body.lastIndexOf('@');
    if (at >= 0) {
      code = body.substring(0, at);
      body = body.substring(at + 1).strip();
    }
    List<Predicate<SemVer>> parts = new ArrayList<>();
    for (String term : body.split("\\s+")) {
      if (term.isEmpty() || term.equals("*") || term.equalsIgnoreCase("x")) {
        continue;
      }
      Matcher m = TERM.matcher(term);
      if (!m.matches()) {
        return Optional.empty();
      }
      String op = m.group(1) == null ? "" : m.group(1);
      int major = Integer.parseInt(m.group(2));
      boolean hasMinor = m.group(3) != null;
      boolean hasPatch = m.group(4) != null;
      SemVer base =
          new SemVer(
              major,
              hasMinor ? Integer.parseInt(m.group(3)) : 0,
              hasPatch ? Integer.parseInt(m.group(4)) : 0);
      parts.add(
          switch (op) {
            case "^" -> {
              SemVer upper =
                  base.major() > 0 || !hasMinor
                      ? new SemVer(base.major() + 1, 0, 0)
                      : new SemVer(0, base.minor() + 1, 0);
              yield v -> v.compareTo(base) >= 0 && v.compareTo(upper) < 0;
            }
            case "~" -> {
              SemVer upper =
                  hasMinor
                      ? new SemVer(base.major(), base.minor() + 1, 0)
                      : new SemVer(base.major() + 1, 0, 0);
              yield v -> v.compareTo(base) >= 0 && v.compareTo(upper) < 0;
            }
            case ">=" -> v -> v.compareTo(base) >= 0;
            case ">" -> v -> v.compareTo(base) > 0;
            case "<=" -> v -> v.compareTo(base) <= 0;
            case "<" -> v -> v.compareTo(base) < 0;
            default -> {
              if (hasPatch) {
                yield v -> v.compareTo(base) == 0;
              }
              SemVer upper =
                  hasMinor
                      ? new SemVer(base.major(), base.minor() + 1, 0)
                      : new SemVer(base.major() + 1, 0, 0);
              yield v -> v.compareTo(base) >= 0 && v.compareTo(upper) < 0;
            }
          });
    }
    Predicate<SemVer> all = parts.stream().reduce(v -> true, Predicate::and);
    return Optional.of(new SemVerRange(code == null || code.isBlank() ? null : code, all));
  }
}
