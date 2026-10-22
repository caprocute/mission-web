package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.analytics.application.ResultCache;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.shared.cache.TenantCache;
import vn.viettel.okrkpi.shared.cache.TenantCacheKey;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kết quả truy vấn trong Valkey: khoá {@code t:<tenant>:analytics:query:<đơn vị>:<kỳ>:<băm>}
 * (NEN-035). Kết quả chỉ gồm số liệu tổng hợp và tên đơn vị (C0, C1). Valkey lỗi thì bỏ qua bộ đệm,
 * truy vấn vẫn chạy.
 */
final class ValkeyResultCache implements ResultCache {

  private static final Logger LOG = LoggerFactory.getLogger(ValkeyResultCache.class);

  private final TenantCache cache;
  private final ObjectMapper json;

  ValkeyResultCache(TenantCache cache, ObjectMapper json) {
    this.cache = cache;
    this.json = json;
  }

  @Override
  public Optional<QueryResult> get(UUID tenant, List<String> key) {
    try {
      Optional<String> v = cache.get(key(tenant, key));
      return v.isEmpty()
          ? Optional.empty()
          : Optional.of(json.readValue(v.get(), QueryResult.class));
    } catch (IOException | RuntimeException e) {
      LOG.warn("Không đọc được bộ đệm phân tích: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public void put(UUID tenant, List<String> key, QueryResult result, Duration ttl) {
    try {
      cache.put(key(tenant, key), json.writeValueAsString(result), ttl);
    } catch (IOException | RuntimeException e) {
      LOG.warn("Không ghi được bộ đệm phân tích: {}", e.getClass().getSimpleName());
    }
  }

  private static TenantCacheKey key(UUID tenant, List<String> key) {
    return TenantCacheKey.forTenant(
        TenantId.of(tenant),
        "analytics",
        "query",
        key.get(0),
        key.subList(1, key.size()).toArray(String[]::new));
  }
}
