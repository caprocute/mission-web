package vn.viettel.okrkpi.config.application.port;

import java.util.Optional;

/**
 * Kho đối tượng gốc của CDN cho gói UI Pack đã phát hành (CORE-08-11, TL-01 "Cơ chế bộ đệm"):
 * {@code ui-packs/{tenant}/{version}/…} bất biến và con trỏ {@code ui-packs/{tenant}/current.json}.
 * Bản thật dùng giao diện S3 của Ceph RGW.
 */
public interface UiPackBundleStore {

  /** Đối tượng bất biến, đặt một năm và {@code immutable}. */
  String IMMUTABLE = "public, max-age=31536000, immutable";

  /** Con trỏ phiên bản hiện hành. */
  String POINTER = "public, max-age=60, stale-while-revalidate=600";

  /** Ghi đè đối tượng {@code key}. */
  void put(String key, byte[] content, String contentType, String cacheControl);

  Optional<byte[]> get(String key);
}
