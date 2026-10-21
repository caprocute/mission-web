package vn.viettel.okrkpi.cel.domain;

import java.util.Locale;

/**
 * Bốn mức xếp loại chất lượng cán bộ theo Quy định 366-QĐ/TW và NĐ 335/2025, NĐ 233/2026.
 *
 * <p>Thứ tự nghiêm ngặt: {@code KHTNV < HTNV < HTTNV < HTXSNV} (tương ứng bí danh đầy đủ {@code
 * KHONG_HOAN_THANH < HOAN_THANH < HOAN_THANH_TOT < HOAN_THANH_XUAT_SAC}).
 *
 * <p>Tham chiếu: NEN-041, BR-09-06, TL-02b SCR-1502.
 */
public enum RatingLevel {
  KHTNV(1, "KHTNV", "KHONG_HOAN_THANH"),
  HTNV(2, "HTNV", "HOAN_THANH"),
  HTTNV(3, "HTTNV", "HOAN_THANH_TOT"),
  HTXSNV(4, "HTXSNV", "HOAN_THANH_XUAT_SAC");

  private final int rank;
  private final String shortCode;
  private final String longCode;

  RatingLevel(int rank, String shortCode, String longCode) {
    this.rank = rank;
    this.shortCode = shortCode;
    this.longCode = longCode;
  }

  public int rank() {
    return rank;
  }

  public String shortCode() {
    return shortCode;
  }

  public String longCode() {
    return longCode;
  }

  /**
   * Phân tích mã mức xếp loại từ chuỗi mã ngắn hoặc mã đầy đủ.
   *
   * @param code chuỗi mã xếp loại
   * @return {@link RatingLevel} tương ứng
   * @throws IllegalArgumentException nếu mã rỗng hoặc không hợp lệ
   */
  public static RatingLevel parse(String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Mã mức xếp loại không được để trống");
    }
    String normalized = code.trim().toUpperCase(Locale.ROOT);
    for (RatingLevel level : values()) {
      if (level.shortCode.equals(normalized) || level.longCode.equals(normalized)) {
        return level;
      }
    }
    throw new IllegalArgumentException("Mã mức xếp loại không hợp lệ: " + code);
  }

  /**
   * Trả về mức xếp loại thấp hơn giữa hai mức đầu vào theo BR-09-06 ({@code min_muc(a, b)}).
   *
   * <p>Bảo đảm tính chất đại số:
   *
   * <ul>
   *   <li>Giao hoán: {@code minMuc(a, b).equals(minMuc(b, a))}
   *   <li>Luỹ đẳng: {@code minMuc(x, x).equals(x)}
   *   <li>Phần tử trung hoà: {@code minMuc(x, "HTXSNV").equals(x)} (với mã ngắn) và {@code
   *       minMuc(x, "HOAN_THANH_XUAT_SAC").equals(x)} (với mã đầy đủ)
   * </ul>
   *
   * @param first mức thứ nhất
   * @param second mức thứ hai
   * @return mã mức thấp hơn (giữ định dạng mã đầy đủ nếu cả hai đầu vào đều dùng mã đầy đủ, ngược
   *     lại trả về mã ngắn chuẩn)
   */
  public static String minMuc(String first, String second) {
    RatingLevel a = parse(first);
    RatingLevel b = parse(second);
    RatingLevel lower = a.rank <= b.rank ? a : b;
    boolean bothLong =
        a.longCode.equalsIgnoreCase(first.trim()) && b.longCode.equalsIgnoreCase(second.trim());
    return bothLong ? lower.longCode : lower.shortCode;
  }

  /** Kiểm tra mức hiện tại có đạt tối thiểu mức yêu cầu hay không. */
  public static boolean isAtLeast(String currentCode, String requiredCode) {
    return parse(currentCode).rank >= parse(requiredCode).rank;
  }
}
