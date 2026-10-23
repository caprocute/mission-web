package vn.viettel.okrkpi.workflow.infrastructure.monitor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.workflow.application.port.MonitorLedger;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Ghi sổ cái thao tác SCR-1506 bằng {@code sys.audit.v1} qua {@code flowable.outbox_event} trong
 * cùng giao dịch (BS-01 "Mọi thao tác đều ghi sổ cái"; tiền lệ {@code OutboxSagaJournal}: {@code
 * sys.audit.v1} là topic kỹ thuật mà SVC-09 ghi sổ). Chuyển người xử lý kèm EVT-21 {@code
 * notify.message.requested.v1} báo người nhận mới (suy luận của hợp đồng). Lý do nằm ở bình luận
 * của engine, không đưa vào sự kiện vì {@code purpose} chỉ nhận mã lý do.
 */
public class OutboxMonitorLedger implements MonitorLedger {

  static final String OBJECT_TYPE = "WF_USER_TASK";
  static final String ACTION_REASSIGN = "CHUYEN_NGUOI_XU_LY";
  static final String ACTION_EXTEND = "GIA_HAN_BUOC";
  static final String PURPOSE = "SCR-1506";

  /** Mẫu thông báo người nhận việc được chuyển (suy luận, SVC-12 cần khai báo). */
  static final String NOTICE_TEMPLATE = "WF_CHUYEN_NGUOI_XU_LY";

  private final OutboxWriter outbox;

  public OutboxMonitorLedger(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void reassigned(MonitoredTask task, UUID newAssigneeId, UUID actorId, int newRevision) {
    audit(task, ACTION_REASSIGN, actorId, newRevision);
    YeuCauThongBao notice =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(newAssigneeId))
            .setTemplateCode(NOTICE_TEMPLATE)
            .setParams(
                "{\"taskId\":\""
                    + task.taskId()
                    + "\",\"businessKey\":\""
                    + task.businessKey().value()
                    + "\"}")
            .setDeepLink(null)
            .setPriority("BINH_THUONG")
            .setAggregateVersion(newRevision)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                TenantId.of(task.tenantId()),
                OBJECT_TYPE,
                aggregateId(task),
                newRevision,
                notice,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }

  @Override
  public void extended(MonitoredTask task, Instant newDueAt, UUID actorId, int newRevision) {
    audit(task, ACTION_EXTEND, actorId, newRevision);
  }

  private void audit(MonitoredTask task, String action, UUID actorId, int newRevision) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(action)
            .setObjectType(OBJECT_TYPE)
            .setObjectId(task.taskId())
            .setOutcome("THANH_CONG")
            .setPurpose(PURPOSE)
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(false)
            .setTargets(null)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.SYS_AUDIT_V1,
                TenantId.of(task.tenantId()),
                OBJECT_TYPE,
                aggregateId(task),
                newRevision,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }

  /** Mã task Flowable là UUID (StrongUuidGenerator); mã khác thì băm thành UUID tên. */
  static UUID aggregateId(MonitoredTask task) {
    try {
      return UUID.fromString(task.taskId());
    } catch (IllegalArgumentException e) {
      return UUID.nameUUIDFromBytes(task.taskId().getBytes(StandardCharsets.UTF_8));
    }
  }
}
