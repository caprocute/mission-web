package vn.viettel.okrkpi.workflow.infrastructure.saga;

import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.workflow.application.saga.SagaJournal;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Ghi vết saga bằng {@code sys.audit.v1} qua {@code flowable.outbox_event} trong cùng giao dịch
 * (BR-08-10). {@code sys.audit.v1} là topic kỹ thuật được mọi outbox phát và SVC-09 ghi sổ cái
 * (catalog.yaml), nên khung không cần topic riêng; bản chiếu tiến độ {@code
 * config.period_lock_step} của MOD-08 đọc cùng luồng này (suy luận, wiki trang 14).
 */
public class OutboxSagaJournal implements SagaJournal {

  static final String OBJECT_SAGA = "SAGA";
  static final String OBJECT_STEP = "SAGA_BUOC";

  private final OutboxWriter outbox;

  public OutboxSagaJournal(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void record(SagaInstance saga, Action action, Integer stepNo, boolean success) {
    String objectId =
        stepNo == null || stepNo < 1 || stepNo > saga.steps().size()
            ? saga.id().toString()
            : saga.id() + ":" + stepNo + ":" + saga.step(stepNo).stepCode();
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction("SAGA_" + action.name())
            .setObjectType(stepNo == null ? OBJECT_SAGA : OBJECT_STEP)
            .setObjectId(objectId)
            .setOutcome(success ? "THANH_CONG" : "LOI")
            .setPurpose(saga.type().name())
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(true)
            .setTargets(null)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.SYS_AUDIT_V1,
                TenantId.of(saga.tenantId()),
                OBJECT_SAGA,
                saga.id(),
                saga.rowVersion() + 1,
                payload,
                Classification.C1)
            .withActor(ActorId.of(saga.initiator())));
  }
}
