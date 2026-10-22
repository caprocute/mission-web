package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Lịch nghỉ quốc gia của DL-07: migration lặp {@code R__config_lich_nghi_quoc_gia.sql} chứa đúng
 * các ngày dựng sẵn của {@link NationalHolidays} với cùng mã dòng, và thư viện ngày làm việc tính
 * đúng tháng có Tết Nguyên đán và tháng 4, 5/2026.
 */
class NationalHolidaySeedTest {

  private static final String MIGRATION = "/db/migration/config/R__config_lich_nghi_quoc_gia.sql";

  @Test
  void repeatableMigrationHasEveryBuiltInDayWithTheSameId() throws IOException {
    String sql;
    try (InputStream in = NationalHolidaySeedTest.class.getResourceAsStream(MIGRATION)) {
      assertThat(in).as(MIGRATION).isNotNull();
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    long rows = sql.lines().filter(l -> l.startsWith("  ('")).count();
    assertThat(rows).isEqualTo(NationalHolidays.all().size());
    for (HolidayDay d : NationalHolidays.all()) {
      assertThat(sql)
          .as(d.date().toString())
          .contains(
              "('"
                  + d.id()
                  + "', '00000000-0000-0000-0000-000000000000'::uuid, '"
                  + d.date()
                  + "', '"
                  + d.kind()
                  + "', ");
    }
    assertThat(sql).contains("ON CONFLICT (tenant_id, calendar_date) DO UPDATE");
  }

  /**
   * Giá trị đối chiếu thủ công trên lịch 2026: tháng 2 có 20 ngày thứ Hai đến thứ Sáu, trừ 5 ngày
   * Tết (16 đến 20/02) còn 15; tháng 4 có 22, trừ nghỉ bù Giỗ Tổ 27/04 và 30/04 còn 20 (Giỗ Tổ
   * 26/04 là Chủ nhật); tháng 5 có 21, trừ 01/05 còn 20.
   */
  @Test
  void workingDaysOfTetAndAprilMay2026() {
    Map<java.time.LocalDate, HolidayDayKind> special =
        NationalHolidays.all().stream()
            .collect(Collectors.toMap(HolidayDay::date, HolidayDay::kind));
    int[] months = new WorkingDays(special).workingDaysByMonth(2026);
    assertThat(months[1]).isEqualTo(15);
    assertThat(months[3]).isEqualTo(20);
    assertThat(months[4]).isEqualTo(20);
  }
}
