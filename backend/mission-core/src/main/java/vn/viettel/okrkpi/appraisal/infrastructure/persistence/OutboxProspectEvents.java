package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectEvents;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.appraisal.v1.HoSoDanhGiaChuyenBuoc;

/**
 * EVT-11 của danh sách phân nhóm (CORE-04-12): thực thể gốc {@code prospect_board}, {@code
 * profileId} mang mã danh sách, {@code stage} {@code PHAN_NHOM}, trạng thái theo enum {@code
 * ProspectBoardStatus} của hợp đồng (suy luận, wiki 14). Phiên bản là {@code row_version} của danh
 * sách nên thứ tự không lẫn với sự kiện của hồ sơ. Không có nhóm, lý do hay người trong danh sách.
 */
public class OutboxProspectEvents implements ProspectEvents {

  static final String AGGREGATE = "prospect_board";
  static final String STAGE = "PHAN_NHOM";

  private final OutboxWriter outbox;

  public OutboxProspectEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void boardStepCompleted(
      TenantId tenant, UUID boardId, String from, String to, UUID actorId, long boardRowVersion) {
    HoSoDanhGiaChuyenBuoc payload =
        HoSoDanhGiaChuyenBuoc.newBuilder()
            .setProfileId(boardId)
            .setFromStatus(from)
            .setToStatus(to)
            .setStage(STAGE)
            .setActorId(actorId)
            .setAggregateVersion((int) boardRowVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_11_HO_SO_DANH_GIA_CHUYEN_BUOC,
                tenant,
                AGGREGATE,
                boardId,
                boardRowVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }
}
