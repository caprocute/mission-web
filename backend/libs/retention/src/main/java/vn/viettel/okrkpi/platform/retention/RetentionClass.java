package vn.viettel.okrkpi.platform.retention;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * Bảy lớp lưu giữ của TL-03 "Lưu giữ, lưu trữ và xoá dữ liệu" (NFR-09). Bảng mặc định, mức sàn và
 * lược đồ giá trị {@code retention-class.v1} giữ đúng như {@code
 * vn.viettel.okrkpi.config.domain.RetentionClass} của mission-core (khoá {@code retention.<LỚP>}
 * trong {@code config.tenant_setting}); hai bên phải sửa cùng nhau.
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

  public static final String SCHEMA = "retention-class.v1";
  private static final LocalDate ANCHOR = LocalDate.of(2000, 1, 1);

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

  public boolean archived() {
    return ending != Ending.XOA_PHAN_VUNG;
  }

  public String settingKey() {
    return "retention." + name();
  }

  public Rule defaults() {
    return new Rule(this, defaultHot, defaultTotal, true);
  }

  /** NFR-09: sổ cái và nhật ký kiểm toán tối thiểu 10 năm. */
  public Period floor() {
    return this == SO_CAI ? Period.ofYears(10) : Period.ofDays(1);
  }

  /** Giá trị cấu hình của tenant; sai lược đồ hoặc dưới mức sàn thì dùng mặc định. */
  public Rule parse(JsonNode value) {
    try {
      return new Rule(
          this,
          Period.parse(value.path("hot").asText()),
          Period.parse(value.path("total").asText()),
          false);
    } catch (RuntimeException e) {
      return defaults();
    }
  }

  static long days(Period p) {
    return ANCHOR.plus(p).toEpochDay() - ANCHOR.toEpochDay();
  }

  /** Thời hạn hiệu lực; {@code proposed} khi đang dùng giá trị đề xuất của TL-03. */
  public record Rule(RetentionClass retentionClass, Period hot, Period total, boolean proposed) {
    public Rule {
      Objects.requireNonNull(retentionClass, "retentionClass");
      if (hot.isNegative() || hot.isZero() || days(hot) > days(total)) {
        throw new IllegalArgumentException("Thời hạn nóng phải dương và không dài hơn tổng");
      }
      if (days(total) < days(retentionClass.floor())) {
        throw new IllegalArgumentException("Tổng thời hạn thấp hơn mức sàn");
      }
    }

    /** Quy tắc giữ lâu hơn ở từng thời hạn, dùng khi một phân vùng chứa nhiều tenant. */
    public Rule longest(Rule other) {
      return new Rule(
          retentionClass,
          days(other.hot) > days(hot) ? other.hot : hot,
          days(other.total) > days(total) ? other.total : total,
          proposed && other.proposed);
    }
  }
}
