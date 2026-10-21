package vn.viettel.okrkpi.org.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Khoảng trên trục hiệu lực {@code [from, to)} của bảng hai trục thời gian (TL-03 chương 7).
 * {@code to == null} nghĩa là còn hiệu lực ({@code valid_to = 'infinity'} trong CSDL).
 */
public record ValidPeriod(LocalDate from, LocalDate to) {

  public ValidPeriod {
    Objects.requireNonNull(from, "from");
    if (to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("valid_from phải trước valid_to");
    }
  }

  public static ValidPeriod openFrom(LocalDate from) {
    return new ValidPeriod(from, null);
  }

  public boolean isOpenEnded() {
    return to == null;
  }

  public boolean contains(LocalDate day) {
    return !day.isBefore(from) && (to == null || day.isBefore(to));
  }

  /** Giao với {@code [otherFrom, otherTo)}; {@code otherTo == null} là vô hạn. */
  public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
    boolean startsBeforeOtherEnds = otherTo == null || from.isBefore(otherTo);
    boolean endsAfterOtherStarts = to == null || to.isAfter(otherFrom);
    return startsBeforeOtherEnds && endsAfterOtherStarts;
  }

  /** Phần giao với {@code [otherFrom, otherTo)}, rỗng nếu không giao. */
  public Optional<ValidPeriod> intersect(LocalDate otherFrom, LocalDate otherTo) {
    if (!overlaps(otherFrom, otherTo)) {
      return Optional.empty();
    }
    LocalDate start = from.isAfter(otherFrom) ? from : otherFrom;
    LocalDate end;
    if (to == null) {
      end = otherTo;
    } else if (otherTo == null) {
      end = to;
    } else {
      end = to.isBefore(otherTo) ? to : otherTo;
    }
    return Optional.of(new ValidPeriod(start, end));
  }
}
