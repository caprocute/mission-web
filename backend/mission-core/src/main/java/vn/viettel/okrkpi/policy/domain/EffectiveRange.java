package vn.viettel.okrkpi.policy.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Khoảng hiệu lực của một phiên bản gói theo kỳ ({@code metadata.hieu_luc.tu_ky}, {@code den_ky}
 * của manifest, TL-01 "Hiệu lực theo kỳ"). {@code from} rỗng là không giới hạn dưới, {@code to}
 * rỗng là còn hiệu lực.
 */
public record EffectiveRange(LocalDate from, LocalDate to) {

  private static final Pattern QUARTER = Pattern.compile("^([0-9]{4})-Q([1-4])$");
  private static final Pattern YEAR = Pattern.compile("^([0-9]{4})$");

  public static final EffectiveRange ALWAYS = new EffectiveRange(null, null);

  /** Kỳ bắt đầu từ {@code day} còn thuộc khoảng hiệu lực. */
  public boolean covers(LocalDate day) {
    return (from == null || !from.isAfter(day)) && (to == null || !to.isBefore(day));
  }

  /** Hai khoảng có chung ít nhất một ngày. */
  public boolean overlaps(EffectiveRange other) {
    boolean startsBeforeOtherEnds = from == null || other.to == null || !from.isAfter(other.to);
    boolean otherStartsBeforeThisEnds = other.from == null || to == null || !other.from.isAfter(to);
    return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
  }

  /**
   * Đọc mã kỳ dạng {@code 2027-Q1}, {@code 2027} hoặc ngày {@code 2027-01-01}; {@code end} chọn
   * ngày cuối kỳ thay vì ngày đầu. Sai dạng trả rỗng.
   */
  public static Optional<LocalDate> periodBound(Object value, boolean end) {
    if (value == null) {
      return Optional.empty();
    }
    String text = String.valueOf(value).strip();
    Matcher q = QUARTER.matcher(text);
    if (q.matches()) {
      LocalDate start =
          LocalDate.of(Integer.parseInt(q.group(1)), (Integer.parseInt(q.group(2)) - 1) * 3 + 1, 1);
      return Optional.of(end ? start.plusMonths(3).minusDays(1) : start);
    }
    Matcher y = YEAR.matcher(text);
    if (y.matches()) {
      int year = Integer.parseInt(y.group(1));
      return Optional.of(end ? LocalDate.of(year, 12, 31) : LocalDate.of(year, 1, 1));
    }
    try {
      return Optional.of(LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
