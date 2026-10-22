package vn.viettel.okrkpi.shared.cache;

import java.time.Duration;
import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * NEN-035, TL-03 "Bộ nhớ đệm Valkey": cổng bộ đệm cache-aside duy nhất mà module nghiệp vụ được
 * dùng. Chỉ nhận {@link TenantCacheKey}, nên không có đường nào ghi khoá thiếu tenant; giá trị là
 * chuỗi đã tuần tự hoá (chỉ dữ liệu C0, C1, ADR-18), Valkey không bao giờ là nguồn sự thật.
 */
public interface TenantCache {

  Optional<String> get(TenantCacheKey key);

  /** Ghi với TTL có nhiễu ngẫu nhiên ±10% để tránh nhiều khoá cùng hết hạn (TL-03). */
  void put(TenantCacheKey key, String value, Duration ttl);

  void evict(TenantCacheKey key);

  /**
   * Xoá mọi khoá của một tenant (chuyển cell, chấm dứt) bằng {@code SCAN t:<tenant>:*} theo lô,
   * không dùng {@code KEYS}; trả số khoá đã xoá.
   */
  long evictTenant(TenantId tenant);
}
