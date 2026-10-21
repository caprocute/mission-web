package vn.viettel.okrkpi.cel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * Tiện ích làm tròn điểm số ở lớp sau biểu thức CEL theo công thức quy đổi điểm (TL-02
 * BR-021..028).
 *
 * <p>Hỗ trợ chế độ {@code HALF_UP} ({@code NUA_LEN}) và {@code DOWN} ({@code CAT_BO}) với số chữ số
 * thập phân cấu hình được (mặc định 2 chữ số thập phân), xử lý chính xác các ca biên như {@code
 * 59.995} và {@code 89.995}.
 */
public final class CelRounding {

  public static final int DEFAULT_SCALE = 2;

  private CelRounding() {}

  /**
   * Giải mã tên chế độ làm tròn từ cấu hình tiếng Việt hoặc chuẩn Java.
   *
   * @param modeName {@code HALF_UP}, {@code NUA_LEN}, {@code DOWN}, {@code CAT_BO}
   * @return {@link RoundingMode} tương ứng
   */
  public static RoundingMode parseRoundingMode(String modeName) {
    Objects.requireNonNull(modeName, "modeName must not be null");
    String normalized = modeName.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "HALF_UP", "NUA_LEN" -> RoundingMode.HALF_UP;
      case "DOWN", "CAT_BO" -> RoundingMode.DOWN;
      default ->
          throw new IllegalArgumentException(
              "Unsupported rounding mode: '"
                  + modeName
                  + "'. Expected HALF_UP (NUA_LEN) or DOWN (CAT_BO).");
    };
  }

  /**
   * Làm tròn giá trị số theo thang bậc thập phân và chế độ chỉ định.
   *
   * @param value giá trị đầu vào ({@link Number} hoặc {@link BigDecimal})
   * @param scale số chữ số thập phân (>= 0)
   * @param mode chế độ làm tròn ({@link RoundingMode#HALF_UP} hoặc {@link RoundingMode#DOWN})
   * @return {@link BigDecimal} đã làm tròn
   */
  public static BigDecimal roundToBigDecimal(Number value, int scale, RoundingMode mode) {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(mode, "mode must not be null");
    if (scale < 0) {
      throw new IllegalArgumentException("scale must be >= 0");
    }
    if (mode != RoundingMode.HALF_UP && mode != RoundingMode.DOWN) {
      throw new IllegalArgumentException(
          "Only HALF_UP (NUA_LEN) and DOWN (CAT_BO) are supported, got: " + mode);
    }
    BigDecimal decimal = toBigDecimal(value);
    return decimal.setScale(scale, mode);
  }

  /** Làm tròn trả về kiểu {@code double} để so khớp với kết quả CEL / JSON. */
  public static double round(Number value, int scale, RoundingMode mode) {
    return roundToBigDecimal(value, scale, mode).doubleValue();
  }

  /**
   * Làm tròn trả về kiểu {@code double} theo tên chế độ cấu hình ({@code NUA_LEN}/{@code CAT_BO}).
   */
  public static double round(Number value, int scale, String modeName) {
    return round(value, scale, parseRoundingMode(modeName));
  }

  /** Chuyển đổi {@link Number} sang {@link BigDecimal} giữ nguyên biểu diễn thập phân. */
  public static BigDecimal toBigDecimal(Number number) {
    Objects.requireNonNull(number, "number must not be null");
    if (number instanceof BigDecimal bd) {
      return bd;
    }
    if (number instanceof Long || number instanceof Integer || number instanceof Short) {
      return BigDecimal.valueOf(number.longValue());
    }
    double d = number.doubleValue();
    if (!Double.isFinite(d)) {
      throw new IllegalArgumentException("Non-finite numeric value is not allowed in CEL: " + d);
    }
    return BigDecimal.valueOf(d);
  }
}
