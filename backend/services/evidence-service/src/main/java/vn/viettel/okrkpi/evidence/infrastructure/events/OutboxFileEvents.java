package vn.viettel.okrkpi.evidence.infrastructure.events;

import java.nio.ByteBuffer;
import vn.viettel.okrkpi.evidence.application.FileEvents;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.sys.v1.EvidenceObjectStored;

/**
 * {@code sys.evidence.object.stored.v1} qua outbox {@code evidence.outbox_event} (danh mục {@code
 * contracts/events/catalog.yaml}; TL-01 SVC-08 ghi {@code evidence.object.stored.v1}).
 */
public class OutboxFileEvents implements FileEvents {

  static final String TOPIC = "sys.evidence.object.stored.v1";
  static final String AGGREGATE = "EVIDENCE_OBJECT";

  private final OutboxWriter outbox;

  public OutboxFileEvents(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void stored(StoredFile f) {
    EvidenceObjectStored payload =
        EvidenceObjectStored.newBuilder()
            .setObjectId(f.objectId())
            .setVersion(f.versionNo())
            .setSha256(ByteBuffer.wrap(f.sha256()))
            .setSizeBytes(f.sizeBytes())
            .setContentType(f.mediaType())
            .setBucketRef(f.bucket() + "/" + f.objectKey())
            .setE2ee(f.e2ee())
            .setRetentionUntil(f.lockUntil())
            .setUploadedBy(f.createdBy())
            .setOwnerRef(
                f.ownerModule() + ":" + (f.ownerRefId() == null ? "" : f.ownerRefId().toString()))
            .setStoredAt(f.createdAt())
            .build();
    outbox.append(
        EventEnvelope.of(
                TOPIC,
                TenantId.of(f.tenantId()),
                AGGREGATE,
                f.objectId(),
                f.versionNo(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(f.createdBy())));
  }
}
