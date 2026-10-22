package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalEvents;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.okr.v1.MucTieuDaDuyet;

/**
 * Ghi EVT-01 (mục tiêu đã duyệt) và EVT-21 (thông báo trả lại tới người soạn) vào {@code
 * okr.outbox_event} cùng giao dịch quyết định (CORE-01-06). Thân sự kiện chỉ có mã (C1), không có ý
 * kiến của người duyệt.
 */
public class OutboxOkrApprovalEvents implements OkrApprovalEvents {

  static final String AGGREGATE = "objective";

  /** Mã mẫu thông báo MSG-02-10 ở SVC-12 (suy luận tên mã). */
  static final String TEMPLATE_RETURNED = "OKR_TRA_LAI";

  private final OutboxWriter outbox;

  public OutboxOkrApprovalEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void approved(
      TenantId tenant, ReviewTarget target, UUID approver, List<UUID> basisIds, long rowVersion) {
    MucTieuDaDuyet payload =
        MucTieuDaDuyet.newBuilder()
            .setObjectiveId(target.id())
            .setPeriodId(target.periodId())
            .setLevel(target.tier())
            .setOwnerUnitId(target.ownerUnitId())
            .setParentObjectiveId(target.parentObjectiveId())
            .setBasisIds(List.copyOf(basisIds))
            .setApprovedBy(approver)
            .setAggregateVersion((int) rowVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_01_MUC_TIEU_DA_DUYET,
                tenant,
                AGGREGATE,
                target.id(),
                rowVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(approver)));
  }

  @Override
  public void returned(TenantId tenant, ReviewTarget target, UUID reviewer, long rowVersion) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(target.createdBy()))
            .setTemplateCode(TEMPLATE_RETURNED)
            .setParams("{\"ma\":" + OutboxOkrCheckinEvents.quote(target.code()) + "}")
            .setDeepLink("/okr/objectives/" + target.id() + "/edit")
            .setPriority("CAO")
            .setAggregateVersion((int) rowVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                AGGREGATE,
                target.id(),
                rowVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(reviewer)));
  }
}
