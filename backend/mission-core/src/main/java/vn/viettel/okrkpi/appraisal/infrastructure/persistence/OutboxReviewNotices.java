package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewNotices;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 báo người được đánh giá hồ sơ bị trả lại để bổ sung (MSG-09-09), ghi {@code
 * appraisal.outbox_event} cùng giao dịch trả lại. Lý do trả lại là C2 nên không đưa vào tham số
 * thông báo; người nhận mở hồ sơ để xem. Mẫu {@code HO_SO_DANH_GIA_TRA_LAI} là suy luận (wiki trang
 * 14).
 */
public class OutboxReviewNotices implements ReviewNotices {

  static final String TEMPLATE = "HO_SO_DANH_GIA_TRA_LAI";

  private final OutboxWriter outbox;

  public OutboxReviewNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void returned(
      TenantId tenant, UUID profileId, UUID personId, int returnCount, long version) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(personId))
            .setTemplateCode(TEMPLATE)
            .setParams("{\"lanTraLai\":" + returnCount + "}")
            .setDeepLink(OutboxSelfAssessmentNotices.DEEP_LINK + profileId)
            .setPriority("CAO")
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            OutboxAppraisalStepEvents.AGGREGATE,
            profileId,
            version,
            payload,
            Classification.C1));
  }
}
