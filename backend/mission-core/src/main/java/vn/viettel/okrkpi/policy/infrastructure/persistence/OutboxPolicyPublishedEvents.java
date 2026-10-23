package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishedEvents;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.policy.v1.PolicyPackDaPhatHanh;

/** EVT-13 {@code policy.pack.published.v1} ghi {@code policy.outbox_event} (CORE-05-10). */
public class OutboxPolicyPublishedEvents implements PolicyPublishedEvents {

  private final OutboxWriter outbox;

  public OutboxPolicyPublishedEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void published(TenantId tenant, PolicyPackVersion v) {
    PolicyPackDaPhatHanh payload =
        PolicyPackDaPhatHanh.newBuilder()
            .setPackId(v.packId())
            .setVersionId(v.id())
            .setContentHash(ByteBuffer.wrap(HexFormat.of().parseHex(v.contentHash())))
            .setEffectiveFrom(v.effectiveFrom())
            .setApprovedBy(v.approvedBy())
            .setTestRunId(v.lastTestRunId())
            .setAggregateVersion((int) v.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_13_POLICY_PACK_DA_PHAT_HANH,
                tenant,
                "policy_pack_version",
                v.id(),
                v.rowVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(v.updatedBy())));
  }
}
