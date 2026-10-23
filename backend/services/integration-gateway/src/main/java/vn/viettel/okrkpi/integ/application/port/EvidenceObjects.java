package vn.viettel.okrkpi.integ.application.port;

import java.io.IOException;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho minh chứng SVC-08 evidence-service (CORE-03-04): nhận tệp gốc văn bản đã ban hành thay cho
 * trình duyệt. Gọi lại cùng {@code idempotencyKey} trả cùng đối tượng.
 */
public interface EvidenceObjects {

  /** Đối tượng đã lưu ở SVC-08. */
  record Stored(
      UUID objectId, int objectVersion, byte[] sha256, long sizeBytes, String mediaType) {}

  Stored ingest(
      TenantId tenant,
      String idempotencyKey,
      UUID ownerRefId,
      UUID requestedBy,
      String fileName,
      String mediaType,
      byte[] content)
      throws IOException;
}
