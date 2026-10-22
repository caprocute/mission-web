package vn.viettel.okrkpi.dossier.domain.specialcase;

import java.util.Map;
import java.util.Objects;

/**
 * Một dòng bảng {@code truong_hop_dac_thu} của Policy Pack (TL-02b QT-11 bảng 13 trường hợp): mã
 * trường hợp ({@code ma}, lưu vào {@code special_case.case_code}), mục PL 6, cách xử lý, hệ quả xếp
 * loại ({@code hieu_ung}), hệ quả khi không đủ điều kiện ({@code hieu_ung_khi_khong_du}, có thể
 * rỗng) và tham số ({@code tham_so}). Ngưỡng của bộ phát hiện đọc từ tham số nên đổi quy định chỉ
 * cần phát hành gói mới.
 */
public record SpecialCaseRule(
    String code,
    int section,
    String legalRef,
    String handling,
    String effect,
    String effectWhenInsufficient,
    Map<String, Object> params) {

  public SpecialCaseRule {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(legalRef, "legalRef");
    Objects.requireNonNull(effect, "effect");
    params = params == null ? Map.of() : java.util.Collections.unmodifiableMap(params);
  }

  /** Tham số số; thiếu hoặc {@code null} (gói chưa quy định) thì rỗng. */
  public Double number(String key) {
    return params.get(key) instanceof Number n ? n.doubleValue() : null;
  }

  /** Tham số số, thiếu thì lấy {@code fallback}. */
  public double number(String key, double fallback) {
    Double v = number(key);
    return v == null ? fallback : v;
  }
}
