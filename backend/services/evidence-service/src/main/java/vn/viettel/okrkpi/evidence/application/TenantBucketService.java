package vn.viettel.okrkpi.evidence.application;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

/**
 * Cấp bucket minh chứng cho tenant khi cấp phát (giao diện nội bộ cho saga DV-03-04): bucket {@code
 * ev-<cell>-<tenant>} có Object Lock chế độ compliance, thời hạn giữ theo tham số. Gọi lại an toàn
 * (bước saga có thể chạy lại).
 */
public class TenantBucketService {

  private final ObjectStorage storage;
  private final String cell;
  private final int lockDays;

  public TenantBucketService(ObjectStorage storage, String cell, int lockDays) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.cell = Objects.requireNonNull(cell, "cell");
    if (lockDays < 1) {
      throw new IllegalArgumentException("Thời hạn khoá phải từ 1 ngày");
    }
    this.lockDays = lockDays;
    ObjectLocation.bucketOf(cell, new UUID(0, 0));
  }

  /** Bảo đảm bucket của tenant tồn tại; trả tên bucket. */
  public String provision(UUID tenant) {
    String bucket = ObjectLocation.bucketOf(cell, tenant);
    storage.ensureLockedBucket(bucket, lockDays);
    return bucket;
  }

  public int lockDays() {
    return lockDays;
  }
}
