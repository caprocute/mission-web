package vn.viettel.okrkpi.org.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Phép tính thuần trên trục hiệu lực của một thực thể, dựa trên các phiên bản đang là hiểu biết hiện tại
 * ({@code recorded_to = 'infinity'}). Không bao giờ sửa phiên bản cũ: phiên bản bị chạm được đóng và chèn lại
 * phần còn giữ cùng phần đổi (BR-01-06, TL-03 chương 7).
 */
public final class Timeline {

  private Timeline() {
  }

  /**
   * Áp thay đổi cho khoảng {@code [from, to)} ({@code to == null} là vô hạn): mỗi phiên bản giao với khoảng được đóng,
   * phần trước và sau khoảng giữ nguyên nội dung, phần giao mang nội dung mới.
   */
  public static <T extends Versioned<T>> VersionPlan<T> applyChange(
      List<T> current, LocalDate from, LocalDate to, UnaryOperator<T> change) {
    return slice(current, from, to, change, true);
  }

  /** Bỏ hiệu lực trong {@code [from, to)}: giữ phần trước và sau, không chèn phần giao. */
  public static <T extends Versioned<T>> VersionPlan<T> removeRange(List<T> current, LocalDate from, LocalDate to) {
    return slice(current, from, to, UnaryOperator.identity(), false);
  }

  /** Chấm dứt hiệu lực từ ngày {@code end} (giải thể, sáp nhập, kết thúc phân công). */
  public static <T extends Versioned<T>> VersionPlan<T> endAt(List<T> current, LocalDate end) {
    return removeRange(current, end, null);
  }

  /** Phiên bản chứa ngày {@code day} trong số phiên bản hiện hành. */
  public static <T extends Versioned<T>> java.util.Optional<T> versionAt(List<T> current, LocalDate day) {
    return current.stream().filter(v -> v.validPeriod().contains(day)).findFirst();
  }

  private static <T extends Versioned<T>> VersionPlan<T> slice(
      List<T> current, LocalDate from, LocalDate to, UnaryOperator<T> change, boolean keepMiddle) {
    if (to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("Khoảng hiệu lực rỗng");
    }
    List<T> sorted = new ArrayList<>(current);
    sorted.sort(Comparator.comparing(v -> v.validPeriod().from()));
    List<java.util.UUID> close = new ArrayList<>();
    List<T> insert = new ArrayList<>();
    for (T version : sorted) {
      ValidPeriod period = version.validPeriod();
      var middle = period.intersect(from, to);
      if (middle.isEmpty()) {
        continue;
      }
      close.add(version.versionId());
      if (period.from().isBefore(middle.get().from())) {
        insert.add(version.withValidPeriod(new ValidPeriod(period.from(), middle.get().from())));
      }
      if (keepMiddle) {
        insert.add(change.apply(version.withValidPeriod(middle.get())));
      }
      LocalDate middleEnd = middle.get().to();
      if (middleEnd != null && (period.to() == null || period.to().isAfter(middleEnd))) {
        insert.add(version.withValidPeriod(new ValidPeriod(middleEnd, period.to())));
      }
    }
    return new VersionPlan<>(close, insert);
  }
}
