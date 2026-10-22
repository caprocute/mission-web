package vn.viettel.okrkpi.evidence.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.domain.PageSize;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/** Hàng đợi dựng ảnh xem trước trên {@code evidence.object_version.preview_status}. */
public interface PreviewQueue {

  /** Trạng thái xem trước của một phiên bản; {@code status} {@code null} khi chưa xếp việc. */
  record State(StoredFile file, String status, List<PageSize> pages) {}

  List<Due> due(int limit);

  Optional<Claim> claim(UUID versionId, Duration lease);

  void retryAt(UUID versionId, Instant at);

  /** {@code DANG_TAO} → {@code SAN_SANG} cùng kích thước các trang. */
  boolean ready(UUID versionId, List<PageSize> pages);

  /** {@code DANG_TAO} → {@code LOI}. */
  boolean failed(UUID versionId);

  Optional<State> state(UUID objectId, Integer versionNo);
}
