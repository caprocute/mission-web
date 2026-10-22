package vn.viettel.okrkpi.config.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lịch quốc gia dựng sẵn (scope {@code QUOC_GIA}) từ dữ liệu khởi tạo DL-07 ({@code
 * data/seed/config/holiday_calendar_<năm>.csv}, bản sao trên classpath cạnh lớp này, kiểm thử bảo
 * đảm hai bản trùng nhau). Dùng cho mọi tenant vì RLS của {@code config.holiday_calendar} không cho
 * tenant đọc dòng của tenant hệ thống; dòng {@code QUOC_GIA} trong bảng của chính tenant (nếu có)
 * ghi đè ngày dựng sẵn. Năm chưa có tệp chỉ nghỉ thứ Bảy, Chủ nhật.
 */
public final class NationalHolidays {

  /** Các năm có tệp lịch; thêm năm mới thì thêm tệp và năm vào đây. */
  static final int[] YEARS = {2026, 2027};

  private static final List<HolidayDay> DAYS = load();

  private NationalHolidays() {}

  /** Mọi ngày quốc gia dựng sẵn. */
  public static List<HolidayDay> all() {
    return DAYS;
  }

  /** Các ngày quốc gia dựng sẵn của năm. */
  public static List<HolidayDay> of(int year) {
    return DAYS.stream().filter(d -> d.date().getYear() == year).toList();
  }

  /** Mã dòng tất định của ngày quốc gia dựng sẵn. */
  public static UUID idOf(LocalDate date) {
    return UUID.nameUUIDFromBytes(("QUOC_GIA:" + date).getBytes(StandardCharsets.UTF_8));
  }

  /** Ngày quốc gia dựng sẵn có mã này không. */
  public static boolean isBuiltIn(UUID id) {
    return DAYS.stream().anyMatch(d -> d.id().equals(id));
  }

  private static List<HolidayDay> load() {
    List<HolidayDay> days = new ArrayList<>();
    for (int year : YEARS) {
      String file = "holiday_calendar_" + year + ".csv";
      try (InputStream in = NationalHolidays.class.getResourceAsStream(file)) {
        if (in == null) {
          throw new IllegalStateException("Thiếu tệp lịch quốc gia " + file);
        }
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.readLine();
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          if (!line.isBlank()) {
            days.add(parse(line));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return List.copyOf(days);
  }

  /** Đọc bốn cột đầu {@code calendar_date,day_kind,name,scope}; cột tên có thể có ngoặc kép. */
  static HolidayDay parse(String line) {
    List<String> cols = columns(line);
    LocalDate date = LocalDate.parse(cols.get(0));
    return new HolidayDay(
        idOf(date),
        date,
        HolidayDayKind.valueOf(cols.get(1)),
        cols.get(2),
        HolidayScope.valueOf(cols.get(3)),
        0);
  }

  static List<String> columns(String line) {
    List<String> cols = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cur.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        cols.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    cols.add(cur.toString());
    return cols;
  }
}
