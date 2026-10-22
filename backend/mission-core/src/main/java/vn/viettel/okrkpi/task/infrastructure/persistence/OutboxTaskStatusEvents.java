package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.api.TaskStatusChangedEvent;
import vn.vtokr.task.v1.TrangThaiNhiemVuDoi;

/** EVT-06 {@code task.task.statuschanged.v1} ghi vào {@code task.outbox_event} cùng giao dịch. */
public class OutboxTaskStatusEvents
    implements vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents {

  static final String AGGREGATE = "task";

  private final OutboxWriter outbox;

  public OutboxTaskStatusEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void statusChanged(TaskStatusChangedEvent e) {
    TrangThaiNhiemVuDoi payload =
        TrangThaiNhiemVuDoi.newBuilder()
            .setTaskId(e.taskId())
            .setFromStatus(e.fromStatus())
            .setToStatus(e.toStatus())
            .setIsOverdue(e.overdue())
            .setReasonCode(e.transition())
            .setChangedBy(e.changedBy())
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI,
                e.tenantId(),
                AGGREGATE,
                e.taskId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.changedBy())));
  }
}
