package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressEvents;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/** EVT-05 {@code task.task.progressed.v1} ghi vào {@code task.outbox_event} cùng giao dịch. */
public class OutboxTaskProgressEvents implements TaskProgressEvents {

  private final OutboxWriter outbox;

  public OutboxTaskProgressEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void progressed(Progressed e) {
    TienDoDaCapNhat payload =
        TienDoDaCapNhat.newBuilder()
            .setTaskId(e.taskId())
            .setPeriodId(e.periodId())
            .setProgressId(e.progressId())
            .setReporterId(e.reporterId())
            .setProgressPct(e.percent().setScale(3, RoundingMode.HALF_UP))
            .setActualValue(
                e.doneQuantity() == null
                    ? null
                    : e.doneQuantity().setScale(4, RoundingMode.HALF_UP))
            .setReportedAt(e.reportedAt().truncatedTo(ChronoUnit.MICROS))
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT,
                e.tenant(),
                OutboxTaskStatusEvents.AGGREGATE,
                e.taskId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.reporterId())));
  }
}
