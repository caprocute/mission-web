package vn.viettel.okrkpi.workflow.domain.template;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Khung cấm triển khai định nghĩa lên engine (BS-01 "Giới hạn để không ảnh hưởng mức tải", ý thứ
 * tư): không trong giờ cao điểm và không trong {@code freezeDays} ngày cuối kỳ, vì đó là lúc tải
 * user task cao nhất và sổ cái niêm phong 5 phút một lần.
 *
 * @param zone múi giờ tính giờ cao điểm
 * @param peakHours các khung giờ cao điểm trong ngày làm việc
 * @param peakOnWeekends giờ cao điểm áp dụng cả thứ Bảy, Chủ nhật
 * @param periodEnds ngày kết thúc các kỳ
 * @param freezeDays số ngày cuối kỳ bị cấm (BS-01: 3), tính cả ngày kết thúc
 */
public record RolloutWindow(
    ZoneId zone,
    List<PeakHours> peakHours,
    boolean peakOnWeekends,
    List<LocalDate> periodEnds,
    int freezeDays) {

  /** Số ngày cuối kỳ bị cấm theo BS-01. */
  public static final int DEFAULT_FREEZE_DAYS = 3;

  public RolloutWindow {
    Objects.requireNonNull(zone, "zone");
    peakHours = List.copyOf(peakHours == null ? List.of() : peakHours);
    periodEnds = List.copyOf(periodEnds == null ? List.of() : periodEnds);
    if (freezeDays < 0) {
      throw new IllegalArgumentException("Số ngày cuối kỳ không được âm");
    }
  }

  /** Khung giờ {@code [from, to)} trong ngày. */
  public record PeakHours(LocalTime from, LocalTime to) {
    public PeakHours {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      if (!from.isBefore(to)) {
        throw new IllegalArgumentException(
            "Khung giờ cao điểm phải có giờ bắt đầu trước giờ kết thúc");
      }
    }

    /** Đọc dạng {@code HH:mm-HH:mm}. */
    public static PeakHours parse(String text) {
      String[] parts = text.strip().split("-");
      if (parts.length != 2) {
        throw new IllegalArgumentException("Khung giờ phải có dạng HH:mm-HH:mm: " + text);
      }
      return new PeakHours(LocalTime.parse(parts[0].strip()), LocalTime.parse(parts[1].strip()));
    }

    boolean contains(LocalTime t) {
      return !t.isBefore(from) && t.isBefore(to);
    }
  }

  /** Lý do cấm tại thời điểm {@code at}, rỗng khi được triển khai. */
  public Optional<String> blockedAt(Instant at) {
    ZonedDateTime local = at.atZone(zone);
    LocalDate day = local.toLocalDate();
    for (LocalDate end : periodEnds) {
      if (freezeDays > 0 && !day.isAfter(end) && day.isAfter(end.minusDays(freezeDays))) {
        return Optional.of(
            "Đang trong " + freezeDays + " ngày cuối kỳ, không triển khai mẫu luồng.");
      }
    }
    boolean weekend =
        local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY;
    if (weekend && !peakOnWeekends) {
      return Optional.empty();
    }
    LocalTime time = local.toLocalTime();
    for (PeakHours peak : peakHours) {
      if (peak.contains(time)) {
        return Optional.of("Đang trong giờ cao điểm, không triển khai mẫu luồng.");
      }
    }
    return Optional.empty();
  }
}
