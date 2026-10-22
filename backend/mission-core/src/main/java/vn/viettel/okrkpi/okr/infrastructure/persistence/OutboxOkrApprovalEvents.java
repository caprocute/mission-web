package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalEvents;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
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

  /** Mã mẫu thông báo huỷ mục tiêu (suy luận tên mã). */
  static final String TEMPLATE_CANCELLED = "OKR_HUY_MUC_TIEU";

  /** Mã mẫu thông báo mục tiêu cấp trên đã điều chỉnh (suy luận tên mã). */
  static final String TEMPLATE_PARENT_REVISED = "OKR_CAP_TREN_DIEU_CHINH";

  private static final ZoneId VIET_NAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private final OutboxWriter outbox;
  private final Supplier<OrgDirectoryApi> org;

  public OutboxOkrApprovalEvents(OutboxWriter outbox, Supplier<OrgDirectoryApi> org) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.org = Objects.requireNonNull(org, "org");
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
    requestNotice(
        tenant,
        target,
        List.of(target.createdBy()),
        TEMPLATE_RETURNED,
        "/okr/objectives/" + target.id() + "/edit",
        reviewer,
        rowVersion);
  }

  @Override
  public void cancelled(TenantId tenant, ReviewTarget target, UUID actor, long rowVersion) {
    OrgDirectoryApi directory = org.get();
    Set<UUID> recipients = new LinkedHashSet<>();
    if (directory != null) {
      recipients.addAll(directory.headsOf(tenant, target.ownerUnitId(), LocalDate.now(VIET_NAM)));
    }
    recipients.add(target.createdBy());
    recipients.remove(actor);
    requestNotice(
        tenant,
        target,
        List.copyOf(recipients),
        TEMPLATE_CANCELLED,
        "/okr?objective=" + target.id(),
        actor,
        rowVersion);
  }

  @Override
  public void parentRevised(
      TenantId tenant, ReviewTarget revision, List<UUID> recipients, UUID actor, long rowVersion) {
    requestNotice(
        tenant,
        revision,
        recipients,
        TEMPLATE_PARENT_REVISED,
        "/okr?objective=" + revision.id(),
        actor,
        rowVersion);
  }

  private void requestNotice(
      TenantId tenant,
      ReviewTarget target,
      List<UUID> recipients,
      String template,
      String deepLink,
      UUID actor,
      long rowVersion) {
    if (recipients.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(recipients))
            .setTemplateCode(template)
            .setParams("{\"ma\":" + OutboxOkrCheckinEvents.quote(target.code()) + "}")
            .setDeepLink(deepLink)
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
            .withActor(ActorId.of(actor)));
  }
}
