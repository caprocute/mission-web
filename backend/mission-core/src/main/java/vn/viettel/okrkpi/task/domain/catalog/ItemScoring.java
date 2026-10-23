package vn.viettel.okrkpi.task.domain.catalog;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dữ liệu chấm điểm mở của mục danh mục (BS-03 DC-01, DC-02): mức của từng loại hệ số (khoá là mã
 * loại do Policy Pack khai báo), mã trục, và khung tỷ lệ chỉnh điểm chuẩn cho dòng cấp dưới cùng
 * mã.
 */
public record ItemScoring(
    Map<String, String> coefficientLevels,
    String axisCode,
    BigDecimal scoreAdjustMin,
    BigDecimal scoreAdjustMax) {

  /** Không mức hệ số, không trục, không khung chỉnh. */
  public static final ItemScoring NONE = new ItemScoring(Map.of(), null, null, null);

  public ItemScoring {
    coefficientLevels =
        coefficientLevels == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new TreeMap<>(coefficientLevels));
  }

  /** Có khung chỉnh: ít nhất một đầu khác rỗng. */
  public boolean bounded() {
    return scoreAdjustMin != null || scoreAdjustMax != null;
  }
}
