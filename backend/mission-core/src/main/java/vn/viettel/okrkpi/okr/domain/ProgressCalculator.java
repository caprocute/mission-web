package vn.viettel.okrkpi.okr.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Công thức tiến độ duy nhất của MOD-01 (TL-02a SCR-0201): tiến độ KR = (hiện tại − đầu) / (chỉ
 * tiêu − đầu) kẹp trong [0; 1]; tiến độ mục tiêu = trung bình có trọng số tiến độ các KR. Web, di
 * động và bảng điều hành đọc cùng con số này từ máy chủ. Lớp thuần, không phụ thuộc Spring.
 *
 * <p>Các trường hợp TL-02a không nói rõ (suy luận):
 *
 * <ul>
 *   <li>Chưa có check-in ({@code current} rỗng) thì tiến độ 0; giá trị đầu rỗng coi là 0.
 *   <li>Chiều {@code GIAM}: (đầu − hiện tại) / (đầu − chỉ tiêu); thiếu giá trị đầu, hoặc đầu không
 *       lớn hơn chỉ tiêu, thì chỉ xét đạt hay chưa đạt ({@code hiện tại ≤ chỉ tiêu}).
 *   <li>Chiều {@code DAT} (giữ đạt một mốc): đạt khi hiện tại không thấp hơn chỉ tiêu, chưa đạt thì
 *       tính như chiều tăng.
 *   <li>Chiều {@code TOI_DA} (không vượt quá, BS-03 DC-04): đạt (1) khi hiện tại không vượt chỉ
 *       tiêu, vượt thì 0.
 *   <li>Loại {@code MOC} (BS-03 DC-04): lõi không quy đổi riêng, hiện tại là số mốc đã đạt và chỉ
 *       tiêu là số mốc nên công thức chiều tăng áp nguyên; quy đổi theo hạn nằm trong gói.
 *   <li>Loại {@code CO_KHONG}: hiện tại khác 0 là "có", tiến độ 1; bằng 0 là 0.
 *   <li>Chỉ tiêu bằng giá trị đầu ở chiều tăng: đạt (1) khi hiện tại không thấp hơn chỉ tiêu.
 *   <li>Mục tiêu không có KR, hoặc tổng trọng số bằng 0, thì tiến độ 0; tổng trọng số nhỏ hơn 1 (KR
 *       nháp) thì chia cho tổng trọng số hiện có.
 * </ul>
 */
public final class ProgressCalculator {

  /** Số chữ số thập phân của tiến độ, khớp cột {@code numeric(6,4)}. */
  public static final int SCALE = 4;

  /** Một KR trong phép tính tiến độ mục tiêu. */
  public record Weighted(BigDecimal weight, BigDecimal progress) {}

  private ProgressCalculator() {}

  public static BigDecimal keyResult(
      String metricType,
      String direction,
      BigDecimal baseline,
      BigDecimal target,
      BigDecimal current) {
    if (current == null || target == null) {
      return zero();
    }
    if ("CO_KHONG".equals(metricType)) {
      return current.signum() != 0 ? one() : zero();
    }
    BigDecimal base = baseline == null ? BigDecimal.ZERO : baseline;
    if ("TOI_DA".equals(direction)) {
      return current.compareTo(target) <= 0 ? one() : zero();
    }
    if ("GIAM".equals(direction)) {
      if (baseline == null || base.compareTo(target) <= 0) {
        return current.compareTo(target) <= 0 ? one() : zero();
      }
      return clamp(ratio(base.subtract(current), base.subtract(target)));
    }
    if ("DAT".equals(direction) && current.compareTo(target) >= 0) {
      return one();
    }
    if (target.compareTo(base) == 0) {
      return current.compareTo(target) >= 0 ? one() : zero();
    }
    return clamp(ratio(current.subtract(base), target.subtract(base)));
  }

  public static BigDecimal objective(Collection<Weighted> keyResults) {
    BigDecimal weights = BigDecimal.ZERO;
    BigDecimal sum = BigDecimal.ZERO;
    for (Weighted kr : keyResults) {
      if (kr.weight() == null || kr.weight().signum() <= 0 || kr.progress() == null) {
        continue;
      }
      weights = weights.add(kr.weight());
      sum = sum.add(kr.weight().multiply(kr.progress()));
    }
    if (weights.signum() == 0) {
      return zero();
    }
    return clamp(sum.divide(weights, SCALE + 2, RoundingMode.HALF_UP));
  }

  private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
    return a.divide(b, SCALE + 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal clamp(BigDecimal v) {
    BigDecimal c =
        v.signum() < 0 ? BigDecimal.ZERO : v.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : v;
    return c.setScale(SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE);
  }

  private static BigDecimal one() {
    return BigDecimal.ONE.setScale(SCALE);
  }
}
