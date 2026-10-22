package vn.viettel.okrkpi.config.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phiên bản ngưỡng phát hiện tám dấu hiệu bất thường của tenant (QT-14, SCR-1401, BR-14-01,
 * BR-14-09, CORE-08-07), lưu trong {@code config.tenant_setting}. Mỗi lần lưu tạo phiên bản mới có
 * lý do; phiên bản cũ được giữ.
 */
public record AnomalyThresholds(
    int version, String reason, Instant updatedAt, UUID updatedBy, List<Signal> signals) {

  /** Tám dấu hiệu ({@code oversight.anomaly_alert.signal_code}). */
  public enum SignalCode {
    TU_CHAM_CAO,
    CAO_BANG,
    MUC_TIEU_DE,
    MINH_CHUNG_TRUNG,
    CAP_NHAT_DON,
    SUA_SAU_KHOA,
    CHE_NHO,
    MIEN_TRU_LAP
  }

  /** Ngưỡng của một dấu hiệu: bật tắt và tham số tên → số. */
  public record Signal(SignalCode code, boolean enabled, Map<String, BigDecimal> params) {
    public Signal {
      Objects.requireNonNull(code, "code");
      params = params == null ? Map.of() : Map.copyOf(params);
    }
  }

  public static final int REASON_MIN = 20;
  public static final int REASON_MAX = 2000;

  /** Lý do của phiên bản mặc định do nền tảng đặt. */
  public static final String DEFAULT_REASON = "Ngưỡng mặc định của nền tảng.";

  public AnomalyThresholds {
    if (version < 1) {
      throw new IllegalArgumentException("Phiên bản ngưỡng phải từ 1");
    }
    signals = List.copyOf(signals);
    if (signals.size() != SignalCode.values().length
        || signals.stream().map(Signal::code).distinct().count() != signals.size()) {
      throw new IllegalArgumentException("Bộ ngưỡng phải có đủ tám dấu hiệu, mỗi dấu hiệu một lần");
    }
  }

  /** Ngưỡng mặc định theo ví dụ của hợp đồng SCR-1401 (suy luận). */
  public static AnomalyThresholds defaults() {
    return new AnomalyThresholds(
        1,
        DEFAULT_REASON,
        null,
        null,
        List.of(
            signal(SignalCode.TU_CHAM_CAO, "consecutiveQuarters", 3, "scoreGap", 5),
            signal(SignalCode.CAO_BANG, "sameLevelPercent", 90),
            signal(SignalCode.MUC_TIEU_DE, "earlyWeeks", 2, "belowPreviousPercent", 20),
            signal(SignalCode.MINH_CHUNG_TRUNG, "ocrSimilarityPercent", 95),
            signal(SignalCode.CAP_NHAT_DON, "lastDaysPercent", 70, "lastDays", 3),
            new Signal(SignalCode.SUA_SAU_KHOA, true, Map.of()),
            signal(SignalCode.CHE_NHO, "taskIncreasePercent", 50),
            signal(SignalCode.MIEN_TRU_LAP, "exemptionsIn2Quarters", 3)));
  }

  /**
   * Phiên bản kế tiếp: các dấu hiệu gửi lên thay dấu hiệu cùng mã, các dấu hiệu khác giữ nguyên.
   *
   * @throws IllegalArgumentException khi lý do sai độ dài hoặc một dấu hiệu lặp lại
   */
  public AnomalyThresholds next(List<Signal> changes, String newReason, UUID by, Instant at) {
    String r = newReason == null ? "" : newReason.strip();
    if (r.length() < REASON_MIN || r.length() > REASON_MAX) {
      throw new IllegalArgumentException(
          "Lý do thay đổi ngưỡng phải từ " + REASON_MIN + " đến " + REASON_MAX + " ký tự");
    }
    if (changes == null || changes.isEmpty()) {
      throw new IllegalArgumentException("Cần ít nhất một dấu hiệu");
    }
    Set<SignalCode> seen = new HashSet<>();
    Map<SignalCode, Signal> merged = new EnumMap<>(SignalCode.class);
    signals.forEach(s -> merged.put(s.code(), s));
    for (Signal s : changes) {
      if (!seen.add(s.code())) {
        throw new IllegalArgumentException("Dấu hiệu " + s.code() + " lặp lại");
      }
      merged.put(s.code(), s);
    }
    return new AnomalyThresholds(version + 1, r, at, by, new ArrayList<>(merged.values()));
  }

  private static Signal signal(SignalCode code, Object... kv) {
    Map<String, BigDecimal> params = new java.util.LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      params.put((String) kv[i], BigDecimal.valueOf(((Number) kv[i + 1]).longValue()));
    }
    return new Signal(code, true, params);
  }
}
