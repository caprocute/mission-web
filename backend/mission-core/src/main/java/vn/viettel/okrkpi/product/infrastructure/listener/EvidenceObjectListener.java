package vn.viettel.okrkpi.product.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.product.application.evidence.EvidenceObjectProjector;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.evidence.v1.ObjectScanned;
import vn.vtokr.sys.v1.EvidenceObjectStored;

/**
 * Consumer của MOD-03 cho sự kiện đối tượng minh chứng của SVC-08 (CORE-03-03): {@code
 * sys.evidence.object.stored.v1} (topic theo danh mục sự kiện và mã của DV-08-02; TL-01 ghi {@code
 * evidence.object.stored.v1}) và {@code evidence.object.scanned.v1}. Gọi qua {@code
 * InboxMessageProcessor} của schema product: sự kiện trùng bị bỏ qua theo {@code event_id}; thứ tự
 * phiên bản đối tượng do bản chiếu tự kiểm, vì hai topic không bảo đảm thứ tự với nhau.
 */
public class EvidenceObjectListener {

  /** Consumer của sự kiện lưu trong {@code product.inbox_processed}. */
  public static final String STORED_CONSUMER = "product.evidence-object-stored";

  /** Consumer của sự kiện quét. */
  public static final String SCANNED_CONSUMER = "product.evidence-object-scanned";

  /** Người ghi khi sự kiện không mang người thực hiện (tác vụ hệ thống của SVC-08). */
  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private final EvidenceObjectProjector projector;

  public EvidenceObjectListener(EvidenceObjectProjector projector) {
    this.projector = Objects.requireNonNull(projector, "projector");
  }

  public void onStored(EvidenceObjectStored event, InboxEnvelope<?> envelope) {
    ByteBuffer sha = event.getSha256().duplicate();
    byte[] bytes = new byte[sha.remaining()];
    sha.get(bytes);
    projector.onStored(
        envelope.tenantId(),
        new StoredObject(
            event.getObjectId(),
            event.getVersion(),
            bytes,
            event.getSizeBytes(),
            event.getContentType(),
            event.getE2ee(),
            null,
            event.getStoredAt()),
        event.getUploadedBy() != null ? event.getUploadedBy() : actor(envelope));
  }

  public void onScanned(ObjectScanned event, InboxEnvelope<?> envelope) {
    projector.onScanned(
        envelope.tenantId(),
        event.getObjectId(),
        event.getVersion(),
        event.getVerdict(),
        actor(envelope));
  }

  private static UUID actor(InboxEnvelope<?> envelope) {
    return envelope.actorId() != null ? envelope.actorId().value() : SYSTEM_ACTOR;
  }
}
