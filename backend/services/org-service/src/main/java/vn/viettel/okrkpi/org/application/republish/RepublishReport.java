package vn.viettel.okrkpi.org.application.republish;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Kết quả một lần {@code org-republish}.
 *
 * @param batchId mã lần phát lại ({@code batchId} của {@code org.bootstrap.v1})
 * @param asOf thời điểm ghi nhận của ảnh chụp
 * @param counts số thực thể theo loại
 * @param bucketHashes băm tổng theo bucket trong phạm vi
 * @param messages số thông điệp đã phát, kể cả hai thông điệp đánh dấu (0 khi chạy thử)
 * @param duration thời gian chạy
 */
public record RepublishReport(
    UUID batchId,
    Instant asOf,
    Map<String, Long> counts,
    Map<String, String> bucketHashes,
    long messages,
    Duration duration) {

  public long entities() {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }
}
