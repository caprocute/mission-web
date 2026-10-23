package vn.viettel.okrkpi.scoring.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Kết quả một quý đã quyết định, đầu vào xếp loại năm ({@code KetQuaQuy} của Policy Pack, QT-10
 * bước 2). Quý giữ phiên bản gói và băm đầu vào của chính nó (BR-10-06), nên năm không tính lại
 * quý.
 *
 * @param rating mức quý đã quyết định; rỗng khi quý không xếp loại (trường hợp đặc thù, PL 6)
 * @param inputHash {@code input_hash} của quý, 64 ký tự hex
 */
public record QuarterResult(
    int quarter,
    BigDecimal score,
    String rating,
    boolean rated,
    String inputHash,
    String policyVersion) {

  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

  public QuarterResult {
    if (quarter < 1 || quarter > 4) {
      throw new IllegalArgumentException("Quý phải từ 1 đến 4");
    }
    Objects.requireNonNull(score, "score");
    rating = rating == null ? "" : rating;
    if (rated && rating.isEmpty()) {
      throw new IllegalArgumentException("Quý có xếp loại phải có mức");
    }
    if (inputHash == null || !HEX.matcher(inputHash).matches()) {
      throw new IllegalArgumentException("Băm đầu vào quý phải là SHA-256 dạng hex thường");
    }
    if (policyVersion == null || policyVersion.isBlank()) {
      throw new IllegalArgumentException("Thiếu phiên bản bộ quy định của quý");
    }
  }

  /** Phần tử {@code KetQuaQuy} của gói. */
  public Map<String, Object> toKetQuaQuy() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("quy", quarter);
    m.put("diem", score.doubleValue());
    m.put("xep_loai", rating);
    m.put("co_xep_loai", rated);
    return m;
  }

  /** Phần tử của tài liệu đầu vào năm: kết quả, băm và phiên bản gói của quý. */
  Map<String, Object> document() {
    Map<String, Object> m = toKetQuaQuy();
    m.put("input_hash", inputHash);
    m.put("policy_version", policyVersion);
    return m;
  }
}
