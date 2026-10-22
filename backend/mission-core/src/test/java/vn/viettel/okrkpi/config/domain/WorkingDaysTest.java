package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;

/**
 * CORE-08-04: thư viện ngày làm việc chạy bộ ca dùng chung {@code
 * data/golden/ngay-lam-viec/ca-ngay-lam-viec.yaml} và đối chiếu phép đếm với {@code
 * StaticWorkingCalendar} của {@code vt-cel} (hàm {@code ngay_lam_viec}).
 */
class WorkingDaysTest {

  @TestFactory
  Stream<DynamicTest> sharedGoldenCasesMatchLibraryAndCel() throws IOException {
    Map<String, Object> doc = load();
    Map<LocalDate, HolidayDayKind> special = new HashMap<>();
    Map<LocalDate, WorkingCalendar.DayKind> celSpecial = new HashMap<>();
    for (Map<String, Object> d : list(doc.get("lich"))) {
      LocalDate date = LocalDate.parse(d.get("ngay").toString());
      special.put(date, HolidayDayKind.valueOf(d.get("loai").toString()));
      celSpecial.put(date, WorkingCalendar.DayKind.valueOf(d.get("loai").toString()));
    }
    WorkingDays days = new WorkingDays(special);
    WorkingCalendar cel = StaticWorkingCalendar.of(celSpecial);
    List<Map<String, Object>> cases = list(doc.get("ca"));
    assertThat(cases).hasSizeGreaterThanOrEqualTo(15);
    return cases.stream()
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("ma") + " " + c.get("mo_ta"),
                    () -> {
                      Object expected = c.get("mong_doi");
                      switch (c.get("phep").toString()) {
                        case "dem" -> {
                          LocalDate from = LocalDate.parse(c.get("tu").toString());
                          LocalDate to = LocalDate.parse(c.get("den").toString());
                          long want = ((Number) expected).longValue();
                          assertThat(days.countWorkingDays(from, to)).isEqualTo(want);
                          assertThat(cel.countWorkingDays(from, to)).isEqualTo(want);
                        }
                        case "cong" ->
                            assertThat(
                                    days.plusWorkingDays(
                                        LocalDate.parse(c.get("tu").toString()),
                                        ((Number) c.get("n")).intValue()))
                                .isEqualTo(LocalDate.parse(expected.toString()));
                        case "la_ngay_lam_viec" -> {
                          LocalDate date = LocalDate.parse(c.get("ngay").toString());
                          assertThat(days.isWorkingDay(date)).isEqualTo(expected);
                          assertThat(cel.isWorkingDay(date)).isEqualTo(expected);
                        }
                        default -> throw new AssertionError("Phép lạ: " + c.get("phep"));
                      }
                    }));
  }

  @Test
  void workingDaysByMonthAndHelpers() {
    WorkingDays national =
        new WorkingDays(
            Map.of(
                LocalDate.parse("2026-02-14"), HolidayDayKind.LAM_BU,
                LocalDate.parse("2026-02-16"), HolidayDayKind.NGHI_LE));
    int[] months = national.workingDaysByMonth(2026);
    assertThat(months).hasSize(12);
    assertThat(months[1]).isEqualTo(20);
    assertThat(months[0]).isEqualTo(22);
    assertThat(national.workingDayOnOrBefore(LocalDate.parse("2026-02-16")))
        .isEqualTo(LocalDate.parse("2026-02-14"));
    assertThat(national.workingDayOnOrBefore(LocalDate.parse("2026-02-17")))
        .isEqualTo(LocalDate.parse("2026-02-17"));
    assertThat(WorkingDays.weekendsOnly().isWorkingDay(LocalDate.parse("2026-02-16"))).isTrue();
    assertThat(national.specialDays()).hasSize(2);
  }

  @Test
  void calendarWithoutWorkingDaysFailsInsteadOfLooping() {
    Map<LocalDate, HolidayDayKind> all = new HashMap<>();
    for (LocalDate d = LocalDate.parse("2026-01-01");
        d.isBefore(LocalDate.parse("2037-01-01"));
        d = d.plusDays(1)) {
      all.put(d, HolidayDayKind.NGHI_LE);
    }
    WorkingDays none = new WorkingDays(all);
    assertThatThrownBy(() -> none.plusWorkingDays(LocalDate.parse("2026-01-01"), 1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validationRules() {
    LocalDate sat = LocalDate.parse("2026-02-14");
    LocalDate mon = LocalDate.parse("2026-02-16");
    assertThat(HolidayDay.validate(sat, HolidayDayKind.LAM_BU, "Làm bù")).isNull();
    assertThat(HolidayDay.validate(mon, HolidayDayKind.LAM_BU, "Làm bù")).contains("thứ Bảy");
    assertThat(HolidayDay.validate(mon, HolidayDayKind.NGHI_LE, " ")).isNotNull();
    assertThat(HolidayDay.validate(null, HolidayDayKind.NGHI_LE, "x")).isNotNull();
    assertThat(HolidayDay.validate(mon, HolidayDayKind.NGHI_LE, "x".repeat(101))).contains("100");
    assertThat(HolidayDay.validate(LocalDate.parse("2019-12-31"), HolidayDayKind.NGHI_LE, "x"))
        .contains("2020");
    assertThat(HolidayDayKind.LAM_BU.working()).isTrue();
    assertThat(HolidayDayKind.NGHI_BU.working()).isFalse();
  }

  @Test
  void nationalCalendarHasDeterministicIds() {
    assertThat(NationalHolidays.of(2026)).hasSize(17);
    assertThat(NationalHolidays.of(2027)).hasSize(14);
    assertThat(NationalHolidays.of(2030)).isEmpty();
    HolidayDay tet = NationalHolidays.of(2026).get(0);
    assertThat(tet.id()).isEqualTo(NationalHolidays.idOf(tet.date()));
    assertThat(NationalHolidays.isBuiltIn(tet.id())).isTrue();
    assertThat(NationalHolidays.isBuiltIn(java.util.UUID.randomUUID())).isFalse();
    assertThat(NationalHolidays.all()).allMatch(d -> d.scope() == HolidayScope.QUOC_GIA);
  }

  @Test
  void nationalCalendarResourcesMatchSeedData() throws IOException {
    Path seed = repoRoot().resolve("data/seed/config");
    for (int year : NationalHolidays.YEARS) {
      String file = "holiday_calendar_" + year + ".csv";
      try (InputStream in = NationalHolidays.class.getResourceAsStream(file)) {
        assertThat(in).as(file).isNotNull();
        assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
            .as("Bản sao %s phải trùng data/seed/config", file)
            .isEqualTo(Files.readString(seed.resolve(file)));
      }
    }
    assertThat(NationalHolidays.columns("a,\"b, \"\"c\"\"\",d"))
        .containsExactly("a", "b, \"c\"", "d");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object o) {
    return (List<Map<String, Object>>) o;
  }

  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("data/golden/ngay-lam-viec"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("Không tìm thấy data/golden/ngay-lam-viec").isNotNull();
    return dir;
  }

  private static Map<String, Object> load() throws IOException {
    try (InputStream in =
        Files.newInputStream(
            repoRoot().resolve("data/golden/ngay-lam-viec/ca-ngay-lam-viec.yaml"))) {
      return new Yaml().load(in);
    }
  }
}
