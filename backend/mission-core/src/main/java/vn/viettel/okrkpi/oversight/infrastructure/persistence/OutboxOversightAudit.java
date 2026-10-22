package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Ghi sự kiện kiểm toán của MOD-07 vào {@code oversight.outbox_event} với topic {@code
 * sys.audit.v1} trong cùng giao dịch (CORE-07-12; cùng cách {@code OutboxConfigAudit}).
 */
public class OutboxOversightAudit implements OversightAuditLog {

  static final String AGGREGATE_TYPE = "OversightAudit";

  private final OutboxWriter outbox;

  public OutboxOversightAudit(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void record(
      TenantId tenantId,
      UUID actorId,
      String action,
      String objectType,
      String objectId,
      String purpose,
      String actorRole) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(action)
            .setObjectType(objectType)
            .setObjectId(objectId)
            .setOutcome("THANH_CONG")
            .setPurpose(purpose)
            .setActorAssignmentId(null)
            .setActorRole(actorRole)
            .setSystem(false)
            .setTargets(null)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.SYS_AUDIT_V1,
                tenantId,
                AGGREGATE_TYPE,
                Ids.newUuidV7(),
                0,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }
}
