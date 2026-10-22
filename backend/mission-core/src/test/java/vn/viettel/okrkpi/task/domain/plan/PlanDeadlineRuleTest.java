package vn.viettel.okrkpi.task.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CORE-02-07: hạn duyệt BR-04-01, các ca tính tay theo tuần lịch thứ Hai đến Chủ nhật. */
class PlanDeadlineRuleTest {

  /** Lịch: thứ Bảy, Chủ nhật nghỉ; {@code nghi} là ngày nghỉ lễ, {@code bu} là ngày làm bù. */
  private static Predicate<LocalDate> calendar(String nghi, String bu) {
    Set<LocalDate> off = dates(nghi);
    Set<LocalDate> on = dates(bu);
    return d ->
        on.contains(d)
            || (!off.contains(d)
                && d.getDayOfWeek() != DayOfWeek.SATURDAY
                && d.getDayOfWeek() != DayOfWeek.SUNDAY);
  }

  private static Set<LocalDate> dates(String s) {
    return s == null || s.isBlank()
        ? Set.of()
        : Arrays.stream(s.split(" ")).map(LocalDate::parse).collect(Collectors.toSet());
  }

  @ParameterizedTest(name = "{0}: {4}")
  @CsvSource(
      delimiter = '|',
      nullValues = "-",
      textBlock =
          """
          2026-10-01 | -                                                  | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2026-10-09
          2027-01-01 | 2027-01-01                                         | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2027-01-08
          2024-04-01 | -                                                  | -          | -                                  | 2024-04-12
          2026-07-01 | -                                                  | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2026-07-10
          2023-01-01 | 2023-01-02                                         | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2023-01-06
          2028-04-01 | -                                                  | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2028-04-07
          2026-04-01 | 2026-04-10                                         | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2026-04-09
          2027-04-01 | -                                                  | 2027-04-10 | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2027-04-10
          2025-01-01 | 2025-01-09 2025-01-10                              | -          | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2025-01-08
          2025-01-01 | 2025-01-06 2025-01-07 2025-01-08 2025-01-09 2025-01-10 | -       | NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2 | 2025-01-13
          2026-10-01 | -                                                  | -          | QUY_TAC_LA                         | 2026-10-09
          """)
  void hanDuyetTheoTuanLichThuHai(
      LocalDate start, String nghi, String bu, String rule, LocalDate expected) {
    assertThat(PlanDeadlineRule.deadline(rule, start, calendar(nghi, bu))).isEqualTo(expected);
  }
}
