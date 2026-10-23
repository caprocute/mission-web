package vn.viettel.okrkpi.workflow.infrastructure.release;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import vn.viettel.okrkpi.workflow.application.port.RolloutCalendar;
import vn.viettel.okrkpi.workflow.domain.template.RolloutWindow;

/**
 * Bộ chuyển mặc định của {@link RolloutCalendar}: giờ cao điểm, múi giờ, ngày kết thúc kỳ và số
 * ngày cuối kỳ bị cấm lấy từ cấu hình {@code okrkpi.workflow.release.window.*}.
 */
public class ConfiguredRolloutCalendar implements RolloutCalendar {

  private final RolloutWindow window;

  public ConfiguredRolloutCalendar(
      String zone,
      List<String> peakHours,
      boolean peakOnWeekends,
      List<String> periodEnds,
      int freezeDays) {
    this.window =
        new RolloutWindow(
            ZoneId.of(zone),
            peakHours.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(RolloutWindow.PeakHours::parse)
                .toList(),
            peakOnWeekends,
            periodEnds.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(LocalDate::parse)
                .toList(),
            freezeDays);
  }

  @Override
  public RolloutWindow window() {
    return window;
  }
}
