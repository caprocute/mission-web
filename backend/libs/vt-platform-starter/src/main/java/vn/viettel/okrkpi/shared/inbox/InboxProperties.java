package vn.viettel.okrkpi.shared.inbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NEN-012 · Cấu hình tiêu thụ sự kiện idempotent ({@code okrkpi.inbox.*}). Khai {@code
 * okrkpi.inbox.schema} là bật hạ tầng inbox cho mô-đun hoặc microservice.
 *
 * @param schema tên schema chứa bảng {@code inbox_processed}, {@code inbox_aggregate_version} và
 *     hàm {@code fn_purge_inbox_processed} (ví dụ {@code task}, {@code scoring})
 * @param registry kết nối Apicurio Registry (nếu để trống {@code url}, tự lấy từ {@code
 *     okrkpi.outbox.registry.url} khi có)
 * @param retry cấu hình thử lại tại chỗ và các bậc topic thử lại
 * @param purge cấu hình tác vụ dọn bảng {@code inbox_processed} quá hạn
 * @param listenerAutoStartup tự động khởi chạy container lắng nghe Kafka cho các phương thức {@link
 *     InboxListener}
 */
@ConfigurationProperties(prefix = "okrkpi.inbox")
public record InboxProperties(
    String schema, Registry registry, Retry retry, Purge purge, Boolean listenerAutoStartup) {

  /** 3 bậc độ trễ mặc định của topic {@code <topic>.retry.<consumer>} theo TL-03: 1, 5, 30 phút. */
  public static final List<Duration> DEFAULT_RETRY_DELAYS =
      List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));

  public InboxProperties {
    registry = registry == null ? new Registry(null, null) : registry;
    retry = retry == null ? new Retry(null, null, null, null) : retry;
    purge = purge == null ? new Purge(null, null, null, null, null) : purge;
    listenerAutoStartup = listenerAutoStartup == null ? Boolean.TRUE : listenerAutoStartup;
  }

  /**
   * @param url địa chỉ API v3 của Apicurio Registry
   * @param properties cấu hình serde bổ sung ({@code apicurio.registry.*})
   */
  public record Registry(String url, Map<String, String> properties) {

    public Registry {
      properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
  }

  /**
   * @param maxInPlaceAttempts tổng số lần thử tại chỗ tối đa (TL-03: mặc định 3 lần)
   * @param initialBackoff độ trễ chờ trước lần thử lại tại chỗ đầu tiên (mặc định 50 ms)
   * @param backoffMultiplier hệ số nhân độ trễ tăng dần giữa các lần thử tại chỗ (mặc định 2.0)
   * @param retryDelays danh sách độ trễ của các bậc {@code <topic>.retry.<consumer>} (mặc định 1m,
   *     5m, 30m)
   */
  public record Retry(
      Integer maxInPlaceAttempts,
      Duration initialBackoff,
      Double backoffMultiplier,
      List<Duration> retryDelays) {

    public Retry {
      maxInPlaceAttempts = maxInPlaceAttempts == null ? 3 : maxInPlaceAttempts;
      if (maxInPlaceAttempts < 1) {
        throw new IllegalArgumentException("maxInPlaceAttempts phải >= 1: " + maxInPlaceAttempts);
      }
      initialBackoff = initialBackoff == null ? Duration.ofMillis(50) : initialBackoff;
      backoffMultiplier = backoffMultiplier == null ? 2.0d : backoffMultiplier;
      retryDelays = retryDelays == null ? DEFAULT_RETRY_DELAYS : List.copyOf(retryDelays);
    }
  }

  /**
   * @param enabled bật job dọn {@code inbox_processed} định kỳ (mặc định {@code true})
   * @param retentionInterval khoảng thời gian giữ dấu vết trong {@code inbox_processed} (TL-03: mặc
   *     định {@code "14 days"}, dài hơn 7 ngày giữ của Kafka)
   * @param batchSize kích thước mỗi lô xoá theo {@code ctid} (TL-03: mặc định 10 000 dòng)
   * @param maxBatchesPerRun số lô tối đa trong một lần chạy định kỳ (mặc định 20 lô)
   * @param cron lịch chạy định kỳ (mặc định {@code "0 15 3 * * *"})
   */
  public record Purge(
      Boolean enabled,
      String retentionInterval,
      Integer batchSize,
      Integer maxBatchesPerRun,
      String cron) {

    public Purge {
      enabled = enabled == null ? Boolean.TRUE : enabled;
      retentionInterval =
          retentionInterval == null || retentionInterval.isBlank() ? "14 days" : retentionInterval;
      batchSize = batchSize == null ? 10_000 : batchSize;
      if (batchSize < 1 || batchSize > 50_000) {
        throw new IllegalArgumentException("batchSize phải trong đoạn 1..50000: " + batchSize);
      }
      maxBatchesPerRun = maxBatchesPerRun == null ? 20 : maxBatchesPerRun;
      if (maxBatchesPerRun < 1) {
        throw new IllegalArgumentException("maxBatchesPerRun phải >= 1: " + maxBatchesPerRun);
      }
      cron = cron == null || cron.isBlank() ? "0 15 3 * * *" : cron;
    }
  }
}
