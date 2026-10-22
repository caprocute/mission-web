package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Ghi thay đổi Policy Pack vào {@code policy.outbox_event} với topic {@code sys.audit.v1} trong
 * cùng giao dịch {@code policyTransactionManager}; SVC-09 ghi sổ cái từ topic này (cùng cách của
 * MOD-08).
 */
public class OutboxPolicyAudit implements PolicyAudit {

  private final OutboxWriter outbox;

  public OutboxPolicyAudit(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void changed(
      TenantId tenantId,
      UUID actorId,
      String action,
      String objectType,
      String objectId,
      UUID aggregateId,
      long version) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(action)
            .setObjectType(objectType)
            .setObjectId(objectId)
            .setOutcome("THANH_CONG")
            .setPurpose(null)
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(false)
            .setTargets(null)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.SYS_AUDIT_V1,
                tenantId,
                objectType,
                aggregateId,
                version,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }
}
