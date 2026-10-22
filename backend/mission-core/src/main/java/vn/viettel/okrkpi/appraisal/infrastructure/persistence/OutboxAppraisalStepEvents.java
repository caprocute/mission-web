package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalStepEvents;
import vn.viettel.okrkpi.appraisal.domain.profile.StepCompleted;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.appraisal.v1.HoSoDanhGiaChuyenBuoc;

/**
 * EVT-11 {@code appraisal.profile.stepcompleted.v1} ghi vào {@code appraisal.outbox_event} cùng
 * giao dịch; không chứa trường C2 (lý do trả lại, giải trình chỉ nằm trong bảng đã mã hoá).
 */
public class OutboxAppraisalStepEvents implements AppraisalStepEvents {

  static final String AGGREGATE = "appraisal_profile";

  private final OutboxWriter outbox;

  public OutboxAppraisalStepEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void stepCompleted(
      TenantId tenant, UUID profileId, StepCompleted step, long aggregateVersion) {
    HoSoDanhGiaChuyenBuoc payload =
        HoSoDanhGiaChuyenBuoc.newBuilder()
            .setProfileId(profileId)
            .setFromStatus(step.from().name())
            .setToStatus(step.to().name())
            .setStage(step.stage().name())
            .setActorId(step.actorId())
            .setAggregateVersion((int) aggregateVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_11_HO_SO_DANH_GIA_CHUYEN_BUOC,
                tenant,
                AGGREGATE,
                profileId,
                aggregateVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(step.actorId())));
  }
}
