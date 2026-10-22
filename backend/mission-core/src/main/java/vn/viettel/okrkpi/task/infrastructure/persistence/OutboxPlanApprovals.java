package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalEvents;
import vn.vtokr.task.v1.KeHoachDaDuyet;

/**
 * EVT-03 {@code task.plan.approved.v1} ghi {@code task.outbox_event} cùng giao dịch chuyển {@code
 * DA_DUYET}; khoá sự kiện là kế hoạch (thứ tự theo kế hoạch, catalog {@code tenant:planId}).
 */
public class OutboxPlanApprovals implements PlanApprovalEvents {

  static final String AGGREGATE = "plan";

  private final OutboxWriter outbox;

  public OutboxPlanApprovals(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void approved(PlanVersionApprovedEvent e) {
    KeHoachDaDuyet payload =
        KeHoachDaDuyet.newBuilder()
            .setPlanId(e.planId())
            .setPlanVersionId(e.versionId())
            .setVersionNo(e.versionNo())
            .setPeriodId(e.periodId())
            .setOwnerType(e.planType())
            .setOwnerId(e.ownerId())
            .setItemCount(e.itemCount())
            .setApprovedBy(e.approvedBy())
            .setSupersededVersionId(e.supersededVersionId())
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_03_KE_HOACH_DA_DUYET,
                e.tenantId(),
                AGGREGATE,
                e.planId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.approvedBy())));
  }
}
