package vn.viettel.okrkpi.evidence.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.domain.ScanRecord;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/**
 * Hàng đợi quét trên {@code evidence.object_version} ({@code scan_status = 'CHO_QUET'}) và kết quả
 * {@code evidence.scan_result}.
 */
public interface ScanQueue {

  /** Một việc đến hạn của tenant bất kỳ (chỉ mã, qua hàm SECURITY DEFINER). */
  record Due(UUID versionId, UUID tenantId) {}

  /** Việc đã nhận: phiên bản cần quét và số lần đã nhận, gồm lần này. */
  record Claim(StoredFile file, int attempt) {}

  /** Kết quả gần nhất của một phiên bản, cho {@code getFileScanResult}. */
  record Status(StoredFile file, Optional<ScanRecord> latest) {}

  List<Due> due(int limit);

  /** Nhận việc trong tenant hiện hành nếu còn chờ và đến hạn; đặt hạn thuê {@code lease}. */
  Optional<Claim> claim(UUID versionId, Duration lease);

  /** Trả việc lại để thử sau {@code at}. */
  void retryAt(UUID versionId, Instant at);

  /** Ghi kết quả và chuyển trạng thái nếu phiên bản còn chờ; {@code false} khi đã có kết luận. */
  boolean record(ScanRecord result);

  Optional<Status> status(UUID objectId, Integer versionNo);

  /** Số phiên bản đang chờ quét của mọi tenant. */
  long pending();
}
