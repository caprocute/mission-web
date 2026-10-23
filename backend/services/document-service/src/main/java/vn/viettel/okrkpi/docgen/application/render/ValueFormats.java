package vn.viettel.okrkpi.docgen.application.render;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;

/**
 * Định dạng số và ngày theo tiếng Việt cho biểu mẫu: dấu phẩy thập phân, dấu chấm phân nhóm, ngày
 * {@code dd/MM/yyyy} theo giờ Việt Nam. Quy ước số chữ số thập phân theo tên trường khi kê khai
 * không khai {@code so_le}: chỉ số ({@code kpi_*}) 3 chữ số như SCR-0903, tổng điểm 1 chữ số
 * (BR-09-03), tỷ lệ ({@code ty_le_*}, giá trị 0..1) in phần trăm tối đa 1 chữ số, số thứ tự không
 * có phần lẻ, còn lại tối đa 2 chữ số và bỏ số 0 thừa (suy luận). Làm tròn nửa lên; số liệu đã được
 * làm tròn ở nguồn nên bước này chỉ cắt hiển thị.
 */
public final class ValueFormats {

  public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private ValueFormats() {}

  /** Số đã định dạng theo quy ước của trường {@code tag}. */
  public static String number(String tag, Integer decimals, Object raw) {
    BigDecimal value = decimal(tag, raw);
    if (tag.startsWith("ty_le_") && decimals == null) {
      return pattern(0, 1).format(value.movePointRight(2)) + "%";
    }
    if (decimals != null) {
      return pattern(decimals, decimals).format(value);
    }
    if (tag.startsWith("kpi_")) {
      return pattern(3, 3).format(value);
    }
    if ("tong_diem".equals(tag)) {
      return pattern(1, 1).format(value);
    }
    if ("stt".equals(tag)) {
      return pattern(0, 0).format(value);
    }
    return pattern(0, 2).format(value);
  }

  /** Ngày dạng {@code yyyy-MM-dd} hoặc thời điểm ISO 8601 có múi giờ, in theo giờ Việt Nam. */
  public static String date(String tag, Object raw) {
    String text = raw.toString();
    try {
      if (text.length() <= 10) {
        return LocalDate.parse(text).format(DATE);
      }
      return OffsetDateTime.parse(text).atZoneSameInstant(VIETNAM).format(DATE);
    } catch (DateTimeParseException e) {
      throw new RejectedDocument("Trường " + tag + " không phải ngày hợp lệ.");
    }
  }

  private static BigDecimal decimal(String tag, Object raw) {
    try {
      return raw instanceof BigDecimal b ? b : new BigDecimal(raw.toString());
    } catch (NumberFormatException e) {
      throw new RejectedDocument("Trường " + tag + " không phải số.");
    }
  }

  private static DecimalFormat pattern(int min, int max) {
    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
    symbols.setDecimalSeparator(',');
    symbols.setGroupingSeparator('.');
    DecimalFormat format = new DecimalFormat("#,##0", symbols);
    format.setMinimumFractionDigits(min);
    format.setMaximumFractionDigits(max);
    format.setRoundingMode(RoundingMode.HALF_UP);
    return format;
  }
}
