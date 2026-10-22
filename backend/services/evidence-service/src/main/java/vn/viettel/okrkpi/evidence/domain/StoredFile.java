package vn.viettel.okrkpi.evidence.domain;

import java.time.Instant;
import java.util.UUID;

/** Đối tượng đã lưu: {@code object_meta} cùng một {@code object_version}. */
public record StoredFile(
    UUID objectId,
    UUID versionId,
    int versionNo,
    UUID tenantId,
    UUID createdBy,
    String ownerModule,
    UUID ownerRefId,
    String mediaType,
    String classification,
    long sizeBytes,
    byte[] sha256,
    String bucket,
    String objectKey,
    String cephVersionId,
    Instant lockUntil,
    String scanStatus,
    Instant createdAt) {

  public boolean e2ee() {
    return "C3".equals(classification);
  }

  public ObjectLocation location() {
    return new ObjectLocation(bucket, objectKey);
  }
}
