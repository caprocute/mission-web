package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Mức tiêu chí chung theo tỷ lệ đáp ứng (BR-09-05): 100% thực hiện tốt, từ 90% đảm bảo, từ 60% cơ
 * bản đảm bảo, dưới 60% không đảm bảo.
 */
public enum CriteriaLevel {
  THUC_HIEN_TOT,
  DAM_BAO,
  CO_BAN_DAM_BAO,
  KHONG_DAM_BAO;

  private static final BigDecimal NINETY = new BigDecimal("0.9000");
  private static final BigDecimal SIXTY = new BigDecimal("0.6000");

  /** Tỷ lệ đáp ứng bốn chữ số thập phân; không có tiêu chí nào thì coi như 0. */
  public static BigDecimal ratio(long met, long total) {
    if (total <= 0) {
      return BigDecimal.ZERO.setScale(4);
    }
    return BigDecimal.valueOf(met).divide(BigDecimal.valueOf(total), 4, RoundingMode.DOWN);
  }

  /** Quy đổi tỷ lệ sang mức. */
  public static CriteriaLevel of(BigDecimal ratio) {
    if (ratio.compareTo(BigDecimal.ONE) >= 0) {
      return THUC_HIEN_TOT;
    }
    if (ratio.compareTo(NINETY) >= 0) {
      return DAM_BAO;
    }
    if (ratio.compareTo(SIXTY) >= 0) {
      return CO_BAN_DAM_BAO;
    }
    return KHONG_DAM_BAO;
  }
}
