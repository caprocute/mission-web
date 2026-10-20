package vn.viettel.okrkpi.shared.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * TL-01 "Timeout mặc định": Valkey lỗi thì bỏ qua bộ đệm. Lỗi đọc, ghi, xoá cache chỉ ghi cảnh báo
 * (không kèm khoá hay giá trị, vì khoá có thể mang định danh) và để lời gọi đi thẳng tới nguồn.
 */
public class LenientCacheErrorHandler implements CacheErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(LenientCacheErrorHandler.class);

  @Override
  public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
    warn("đọc", cache, exception);
  }

  @Override
  public void handleCachePutError(
      RuntimeException exception, Cache cache, Object key, Object value) {
    warn("ghi", cache, exception);
  }

  @Override
  public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
    warn("xoá", cache, exception);
  }

  @Override
  public void handleCacheClearError(RuntimeException exception, Cache cache) {
    warn("dọn", cache, exception);
  }

  private static void warn(String op, Cache cache, RuntimeException exception) {
    log.warn(
        "Bỏ qua bộ đệm khi {} cache={} loi={}",
        op,
        cache == null ? null : cache.getName(),
        exception.getClass().getSimpleName());
  }
}
