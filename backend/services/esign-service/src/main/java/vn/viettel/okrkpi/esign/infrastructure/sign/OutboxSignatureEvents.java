package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.nio.ByteBuffer;
import java.util.List;
import vn.viettel.okrkpi.esign.application.sign.SignatureEvents;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/** EVT-18 {@code esign.signature.completed.v1} qua outbox của schema {@code esign}. */
public class OutboxSignatureEvents implements SignatureEvents {

  static final String AGGREGATE = "SIGN_REQUEST";

  private final OutboxWriter outbox;

  public OutboxSignatureEvents(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void completed(SignRequest request, CertificateRef certificate) {
    List<vn.vtokr.esign.v1.SignedItem> items =
        request.items().stream()
            .map(
                (SignItem i) ->
                    vn.vtokr.esign.v1.SignedItem.newBuilder()
                        .setDocumentSha256(ByteBuffer.wrap(i.documentSha256()))
                        .setSignedObjectKey(i.signedObjectKey())
                        .build())
            .toList();
    long version = request.rowVersion() + 1;
    KySoDaHoanTat payload =
        KySoDaHoanTat.newBuilder()
            .setSignRequestId(request.id())
            .setPurpose(request.purpose().name())
            .setSourceModule(request.sourceModule())
            .setSourceRefId(request.sourceRefId())
            .setItems(items)
            .setCertSha256(ByteBuffer.wrap(certificate.certSha256()))
            .setSignedAt(request.completedAt())
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
                TenantId.of(request.tenantId()),
                AGGREGATE,
                request.id(),
                version,
                payload,
                Classification.C1)
            .withActor(ActorId.of(request.signerPersonId())));
  }
}
