package vn.viettel.okrkpi.oversight.domain.complaint;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Một điều chỉnh đề nghị (SCR-1202 trường 7, {@code ComplaintAdjustment}). Giá trị là số (hệ số,
 * điểm) hoặc chuỗi (mức xếp loại); {@code oldValue} có thể vắng.
 */
public record ComplaintAdjustment(
    String targetKey, Object oldValue, Object newValue, String reason) {

  public ComplaintAdjustment {
    Objects.requireNonNull(targetKey, "targetKey");
    oldValue = normalize(oldValue);
    newValue = normalize(newValue);
  }

  private static Object normalize(Object v) {
    if (v instanceof BigDecimal || v == null) {
      return v;
    }
    if (v instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return v.toString();
  }
}
