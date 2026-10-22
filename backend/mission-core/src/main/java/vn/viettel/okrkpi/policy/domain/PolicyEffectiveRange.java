package vn.viettel.okrkpi.policy.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Khoảng hiệu lực của một phiên bản gói theo kỳ ({@code metadata.hieu_luc.tu_ky}, {@code den_ky}
 * của manifest, TL-01 "Hiệu lực theo kỳ"). Kỳ viết {@code 2027-Q1}, {@code 2027-03}, {@code 2027}
 * hoặc ngày {@code 2027-01-01}; {@code tu_ky} trống thì lấy {@code effective_from} của phiên bản,
 * cả hai trống là hiệu lực từ đầu (suy luận). {@code den_ky} trống là chưa có hạn cuối.
 *
 * @param from ngày đầu của {@code tu_ky}, {@code null} là không giới hạn dưới
 * @param to ngày cuối của {@code den_ky}, {@code null} là không giới hạn trên
 */
public record PolicyEffectiveRange(LocalDate from, LocalDate to) {

  private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$");
  private static final Pattern MONTH = Pattern.compile("^(\\d{4})-(\\d{2})$");
  private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  /** Dựng từ manifest; rỗng khi {@code tu_ky} hoặc {@code den_ky} viết sai dạng. */
  public static Optional<PolicyEffectiveRange> of(
      String tuKy, String denKy, LocalDate effectiveFrom) {
    LocalDate from = effectiveFrom;
    if (tuKy != null && !tuKy.isBlank()) {
      Optional<LocalDate[]> start = bounds(tuKy.strip());
      if (start.isEmpty()) {
        return Optional.empty();
      }
      from = start.get()[0];
    }
    LocalDate to = null;
    if (denKy != null && !denKy.isBlank()) {
      Optional<LocalDate[]> end = bounds(denKy.strip());
      if (end.isEmpty()) {
        return Optional.empty();
      }
      to = end.get()[1];
    }
    if (from != null && to != null && to.isBefore(from)) {
      return Optional.empty();
    }
    return Optional.of(new PolicyEffectiveRange(from, to));
  }

  /** Kỳ bắt đầu ngày {@code periodStart} nằm trong khoảng. */
  public boolean covers(LocalDate periodStart) {
    return (from == null || !from.isAfter(periodStart))
        && (to == null || !to.isBefore(periodStart));
  }

  public boolean overlaps(PolicyEffectiveRange other) {
    return (to == null || other.from == null || !other.from.isAfter(to))
        && (other.to == null || from == null || !from.isAfter(other.to));
  }

  /** Khoá sắp xếp "tu_ky lớn nhất trước". */
  public LocalDate start() {
    return from == null ? LocalDate.MIN : from;
  }

  private static Optional<LocalDate[]> bounds(String ky) {
    try {
      Matcher m = QUARTER.matcher(ky);
      if (m.matches()) {
        LocalDate s =
            LocalDate.of(
                Integer.parseInt(m.group(1)), (Integer.parseInt(m.group(2)) - 1) * 3 + 1, 1);
        return Optional.of(new LocalDate[] {s, s.plusMonths(3).minusDays(1)});
      }
      m = MONTH.matcher(ky);
      if (m.matches()) {
        YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return Optional.of(new LocalDate[] {ym.atDay(1), ym.atEndOfMonth()});
      }
      m = YEAR.matcher(ky);
      if (m.matches()) {
        int y = Integer.parseInt(m.group(1));
        return Optional.of(new LocalDate[] {LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)});
      }
      if (DATE.matcher(ky).matches()) {
        LocalDate d = LocalDate.parse(ky);
        return Optional.of(new LocalDate[] {d, d});
      }
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
