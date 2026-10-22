package vn.viettel.okrkpi.config.infrastructure.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import vn.viettel.okrkpi.config.application.port.UiPackBundleStore;

/**
 * Kho trong bộ nhớ khi chưa cấu hình bucket (máy phát triển, kiểm thử đơn vị); không dùng cho môi
 * trường có CDN.
 */
public class InMemoryUiPackBundleStore implements UiPackBundleStore {

  private record Entry(byte[] content, String contentType, String cacheControl) {}

  private final Map<String, Entry> objects = new ConcurrentHashMap<>();

  @Override
  public void put(String key, byte[] content, String contentType, String cacheControl) {
    objects.put(key, new Entry(content.clone(), contentType, cacheControl));
  }

  @Override
  public Optional<byte[]> get(String key) {
    return Optional.ofNullable(objects.get(key)).map(e -> e.content().clone());
  }

  /** Chỉ thị bộ đệm của đối tượng, để kiểm thử. */
  public Optional<String> cacheControl(String key) {
    return Optional.ofNullable(objects.get(key)).map(Entry::cacheControl);
  }
}
