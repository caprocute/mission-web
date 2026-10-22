package vn.viettel.okrkpi.task.domain.mine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Khối lượng việc của người chủ trì (BR-05-11): đếm việc còn mở theo tuần ISO chứa hạn; vượt ngưỡng
 * tham số thì cảnh báo người giao bằng MSG-05-04, không chặn.
 */
public final class Workload {

  public static final String MSG_05_04 =
      "Đồng chí {tên} đang chủ trì {n} việc trong tuần {k}. Cân nhắc phân công lại.";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

  private Workload() {}

  /** Thứ Hai của tuần chứa {@code day}. */
  public static LocalDate weekOf(LocalDate day) {
    return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  /** Số tuần ISO của {@code day}, dùng cho {@code {k}} của MSG-05-04. */
  public static int weekNumber(LocalDate day) {
    return day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
  }

  /** Nhãn tuần "Tuần k (dd/MM–dd/MM)". */
  public static String label(LocalDate monday) {
    return "Tuần "
        + weekNumber(monday)
        + " ("
        + monday.format(DAY)
        + "–"
        + monday.plusDays(6).format(DAY)
        + ")";
  }

  /** Đếm hạn theo tuần cho {@code weeks} tuần liên tiếp bắt đầu từ tuần chứa {@code from}. */
  public static Map<LocalDate, Integer> byWeek(
      Collection<LocalDate> dueDates, LocalDate from, int weeks) {
    Map<LocalDate, Integer> out = new LinkedHashMap<>();
    LocalDate first = weekOf(from);
    for (int i = 0; i < weeks; i++) {
      out.put(first.plusWeeks(i), 0);
    }
    for (LocalDate due : dueDates) {
      out.computeIfPresent(weekOf(due), (k, v) -> v + 1);
    }
    return out;
  }

  /** Số việc chủ trì có hạn trong tuần chứa {@code due}, cộng cả việc đang soạn để giao. */
  public static int inWeekOf(List<LocalDate> dueDates, LocalDate due) {
    LocalDate week = weekOf(due);
    return (int) dueDates.stream().filter(d -> weekOf(d).equals(week)).count() + 1;
  }

  /** Nội dung MSG-05-04 đã điền tham số. */
  public static String message(String name, int n, LocalDate due) {
    if (name == null || name.isBlank()) {
      return "Người chủ trì được chọn đang chủ trì "
          + n
          + " việc trong tuần "
          + weekNumber(due)
          + ". Cân nhắc phân công lại.";
    }
    return MSG_05_04
        .replace("{tên}", name)
        .replace("{n}", Integer.toString(n))
        .replace("{k}", Integer.toString(weekNumber(due)));
  }
}
