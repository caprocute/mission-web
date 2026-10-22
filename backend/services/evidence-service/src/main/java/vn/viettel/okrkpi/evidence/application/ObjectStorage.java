package vn.viettel.okrkpi.evidence.application;

import java.time.Instant;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

/** Cổng kho đối tượng S3 (Ceph RGW); hiện thực ở hạ tầng bằng AWS SDK for Java v2. */
public interface ObjectStorage {

  /** Đối tượng đã ghi: mã phiên bản RGW và hạn khoá compliance. */
  record StoredObject(ObjectLocation location, String versionId, Instant lockedUntil) {}

  /**
   * Tạo bucket có versioning và Object Lock (bật từ lúc tạo, không bật sau được), đặt khoá mặc định
   * chế độ compliance {@code lockDays} ngày. Gọi lại khi bucket đã có thì không lỗi.
   *
   * @return {@code true} khi bucket vừa được tạo
   */
  boolean ensureLockedBucket(String bucket, int lockDays);

  /** Ghi nội dung, khoá chế độ compliance đến {@code lockUntil}. */
  StoredObject put(ObjectLocation location, byte[] content, String mediaType, Instant lockUntil);

  /** Đọc một phiên bản. */
  byte[] read(ObjectLocation location, String versionId);
}
