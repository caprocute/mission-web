package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.nio.ByteBuffer;
import vn.viettel.okrkpi.docgen.application.render.RenderEvents;
import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderOutput;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;

/** EVT-17 {@code dossier.form.generated.v1} qua outbox {@code docgen.outbox_event}. */
public class OutboxRenderEvents implements RenderEvents {

  static final String AGGREGATE = "RENDER_JOB";

  private final OutboxWriter outbox;

  public OutboxRenderEvents(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void formGenerated(RenderJob job, Template template, RenderOutput output, long version) {
    HoSoSoDaSinh payload =
        HoSoSoDaSinh.newBuilder()
            .setRenderJobId(job.id())
            .setFormInstanceId(job.formInstanceId())
            .setFormCode(template.formCode())
            .setObjectKey(output.objectKey())
            .setPdfSha256(ByteBuffer.wrap(output.pdfSha256()))
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_17_HO_SO_SO_DA_SINH,
                TenantId.of(job.tenantId()),
                AGGREGATE,
                job.id(),
                version,
                payload,
                Classification.C1)
            .withActor(ActorId.of(job.createdBy())));
  }
}
