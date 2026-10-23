package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import vn.viettel.okrkpi.esign.application.sign.SignatureEvents;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
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

  /**
   * Thực thể gốc của EVT-18 từng tài liệu trong lô (DV-10-06, suy luận): mỗi tài liệu một mã, phiên
   * bản 1, để inbox kiểm thứ tự theo thực thể không bỏ sự kiện của các tài liệu ký song song.
   */
  static final String ITEM_AGGREGATE = "SIGN_ITEM";

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

  @Override
  public void itemSigned(
      SignRequestRepository.BatchHeader request,
      SignItem item,
      CertificateRef certificate,
      Instant signedAt) {
    KySoDaHoanTat payload =
        KySoDaHoanTat.newBuilder()
            .setSignRequestId(request.id())
            .setPurpose(request.purpose().name())
            .setSourceModule(request.sourceModule())
            .setSourceRefId(request.sourceRefId())
            .setItems(
                List.of(
                    vn.vtokr.esign.v1.SignedItem.newBuilder()
                        .setDocumentSha256(ByteBuffer.wrap(item.documentSha256()))
                        .setSignedObjectKey(item.signedObjectKey())
                        .build()))
            .setCertSha256(ByteBuffer.wrap(certificate.certSha256()))
            .setSignedAt(signedAt)
            .setAggregateVersion(1)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
                TenantId.of(request.tenantId()),
                ITEM_AGGREGATE,
                item.id(),
                1,
                payload,
                Classification.C1)
            .withActor(ActorId.of(request.signerPersonId())));
  }
}
