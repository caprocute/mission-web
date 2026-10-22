package vn.viettel.okrkpi.config.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * Bảy lớp lưu giữ của TL-03 "Lưu giữ, lưu trữ và xoá dữ liệu" (NFR-09, DL-13). Mỗi lớp có thời hạn
 * nóng (còn trong PostgreSQL), tổng thời hạn và cách kết thúc. Giá trị mặc định là đề xuất của
 * TL-03; thời hạn chính thức do cơ quan chủ quản quyết định và ghi ở {@code config.tenant_setting}
 * khoá {@code retention.<LỚP>}. Job lưu giữ ({@code backend/libs/retention}) giữ cùng bảng mặc định
 * và mức sàn này.
 */
public enum RetentionClass {
  HO_SO_CAN_BO(Period.ofYears(5), Period.ofYears(20), Ending.CHUYEN_LUU_TRU_LICH_SU),
  KHIEU_NAI(Period.ofYears(5), Period.ofYears(20), Ending.CHUYEN_LUU_TRU_LICH_SU),
  THUC_HIEN(Period.ofYears(3), Period.ofYears(10), Ending.XOA_BANG_MAT_MA),
  SO_CAI(Period.ofYears(2), Period.ofYears(20), Ending.THEO_HO_SO_DAI_NHAT),
  VET_TINH(Period.ofYears(2), Period.ofYears(10), Ending.XOA_BANG_MAT_MA),
  VAN_HANH(Period.ofYears(1), Period.ofYears(1), Ending.XOA_PHAN_VUNG),
  KY_THUAT(Period.ofDays(14), Period.ofDays(14), Ending.XOA_PHAN_VUNG);

  /** Cách kết thúc khi hết tổng thời hạn. */
  public enum Ending {
    CHUYEN_LUU_TRU_LICH_SU,
    THEO_HO_SO_DAI_NHAT,
    XOA_BANG_MAT_MA,
    XOA_PHAN_VUNG
  }

  /** Mốc so sánh hai {@link Period} (năm nhuận không làm đổi thứ tự so sánh ở đây). */
  private static final LocalDate ANCHOR = LocalDate.of(2000, 1, 1);

  /** NFR-09: sổ cái và nhật ký kiểm toán giữ tối thiểu 10 năm. */
  private static final Period LEDGER_FLOOR = Period.ofYears(10);

  private final Period defaultHot;
  private final Period defaultTotal;
  private final Ending ending;

  RetentionClass(Period defaultHot, Period defaultTotal, Ending ending) {
    this.defaultHot = defaultHot;
    this.defaultTotal = defaultTotal;
    this.ending = ending;
  }

  public Ending ending() {
    return ending;
  }

  /** Lớp có lưu trữ Parquet trước khi kết thúc ({@code VAN_HANH}, {@code KY_THUAT} không có). */
  public boolean archived() {
    return ending != Ending.XOA_PHAN_VUNG;
  }

  /** Khoá cấu hình của lớp trong {@code config.tenant_setting}. */
  public String settingKey() {
    return "retention." + name();
  }

  /** Thời hạn mặc định (đề xuất TL-03). */
  public Rule defaults() {
    return new Rule(this, defaultHot, defaultTotal, true);
  }

  /** Tổng thời hạn tối thiểu được phép đặt. */
  public Period floor() {
    return this == SO_CAI ? LEDGER_FLOOR : Period.ofDays(1);
  }

  /**
   * Thời hạn hiệu lực của một lớp.
   *
   * @param proposed {@code true} khi đang dùng giá trị đề xuất của TL-03 (tenant chưa cấu hình)
   */
  public record Rule(RetentionClass retentionClass, Period hot, Period total, boolean proposed) {
    public Rule {
      Objects.requireNonNull(retentionClass, "retentionClass");
      Objects.requireNonNull(hot, "hot");
      Objects.requireNonNull(total, "total");
      if (hot.isNegative() || hot.isZero()) {
        throw new IllegalArgumentException("Thời hạn nóng phải dương");
      }
      if (days(hot) > days(total)) {
        throw new IllegalArgumentException("Thời hạn nóng không được dài hơn tổng thời hạn");
      }
      if (days(total) < days(retentionClass.floor())) {
        throw new IllegalArgumentException(
            "Tổng thời hạn của lớp "
                + retentionClass.name()
                + " không được thấp hơn "
                + retentionClass.floor());
      }
    }

    private static long days(Period p) {
      return ANCHOR.plus(p).toEpochDay() - ANCHOR.toEpochDay();
    }
  }
}
