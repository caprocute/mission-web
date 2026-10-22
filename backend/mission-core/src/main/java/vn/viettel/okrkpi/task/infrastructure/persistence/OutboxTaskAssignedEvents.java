package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.task.v1.NhiemVuDaGiao;

/**
 * EVT-04 {@code task.task.assigned.v1} và EVT-21 (mẫu {@code NHIEM_VU_DUOC_GIAO}, MSG-05-14) ghi
 * vào {@code task.outbox_event} cùng giao dịch giao việc.
 */
public class OutboxTaskAssignedEvents implements TaskAssignedEvents {

  static final String TEMPLATE_ASSIGNED = "NHIEM_VU_DUOC_GIAO";

  private final OutboxWriter outbox;
  private final ObjectMapper json;

  public OutboxTaskAssignedEvents(OutboxWriter outbox, ObjectMapper json) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void assigned(Assigned e) {
    NhiemVuDaGiao payload =
        NhiemVuDaGiao.newBuilder()
            .setTaskId(e.taskId())
            .setPlanItemId(e.planItemId())
            .setTitle(e.title())
            .setLeadAssigneeId(e.leadAssigneeId())
            .setAssigneeIds(e.coAssigneeIds())
            .setDueDate(e.dueDate())
            .setIsKeyTask(e.keyTask())
            .setIsSecret(e.restricted())
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_04_NHIEM_VU_DA_GIAO,
                e.tenant(),
                OutboxTaskStatusEvents.AGGREGATE,
                e.taskId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.actorId())));
    if (e.notifyIds().isEmpty()) {
      return;
    }
    ObjectNode params = json.createObjectNode();
    params.put("maNhiemVu", e.code());
    params.put("tenNhiemVu", e.title());
    params.put("han", e.dueDate().toString());
    YeuCauThongBao notice =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(e.notifyIds())
            .setTemplateCode(TEMPLATE_ASSIGNED)
            .setParams(params.toString())
            .setDeepLink("/tasks/" + e.taskId())
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                e.tenant(),
                OutboxTaskStatusEvents.AGGREGATE,
                e.taskId(),
                e.aggregateVersion(),
                notice,
                Classification.C1)
            .withActor(ActorId.of(e.actorId())));
  }
}
