package vn.viettel.okrkpi.task.application.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tham số Policy Pack dùng cho danh mục: hệ số mức khó để gợi ý điểm (SCR-0401) và danh sách từ mơ
 * hồ (BR-04-03). Bản mặc định {@link #defaults()} dùng tới khi Policy Pack có các tham số này
 * (CORE-05-03, CORE-01-04).
 */
public interface CatalogPolicy {

  /** Hệ số theo mức khó 1..5; mức thiếu có hệ số 1. */
  Map<Integer, BigDecimal> difficultyFactors(TenantId tenant);

  /** Điểm cơ sở của một mức khó để gợi ý điểm chuẩn. */
  BigDecimal basePerLevel(TenantId tenant);

  List<String> vagueWords(TenantId tenant);

  /** Tham số mặc định: mức 4 hệ số 1,2 (TL-02a SCR-0401); từ mơ hồ theo TL-02a và WEB-025. */
  static CatalogPolicy defaults() {
    return new CatalogPolicy() {
      @Override
      public Map<Integer, BigDecimal> difficultyFactors(TenantId tenant) {
        return Map.of(4, new BigDecimal("1.2"));
      }

      @Override
      public BigDecimal basePerLevel(TenantId tenant) {
        return BigDecimal.valueOf(5);
      }

      @Override
      public List<String> vagueWords(TenantId tenant) {
        return List.of("tăng cường", "đẩy mạnh", "kịp thời", "nâng cao", "tích cực", "chú trọng");
      }
    };
  }
}
