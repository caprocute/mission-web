package vn.viettel.okrkpi.dossier.domain.specialcase;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * Một trường hợp phát hiện được: quy tắc, hệ quả đã chọn, khoảng áp dụng và chi tiết (C2, mã hoá
 * trước khi lưu vào {@code detail_enc}).
 */
public record Detection(
    SpecialCaseRule rule,
    String effect,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Map<String, Object> detail) {

  public Detection {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(effect, "effect");
    detail = detail == null ? Map.of() : java.util.Collections.unmodifiableMap(detail);
  }

  public String code() {
    return rule.code();
  }
}
