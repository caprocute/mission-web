package vn.viettel.okrkpi.config.application;

import java.util.List;
import vn.viettel.okrkpi.config.domain.Period;

/**
 * Kỳ kèm phần suy ra khi đọc: kỳ chưa tới ngày bắt đầu ("Đã tạo") và cảnh báo lịch (BR-08-12). Cảnh
 * báo là câu tiếng Việt cho người dùng, không chứa mã kỹ thuật.
 */
public record PeriodView(Period period, boolean notStarted, List<String> warnings) {

  public PeriodView {
    warnings = List.copyOf(warnings);
  }
}
