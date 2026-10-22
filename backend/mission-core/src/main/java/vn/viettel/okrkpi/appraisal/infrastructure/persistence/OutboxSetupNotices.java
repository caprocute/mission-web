package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.setup.port.SetupNotices;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 báo cán bộ phiếu tự đánh giá đã mở (QT-09 bước 3), ghi {@code appraisal.outbox_event} cùng
 * giao dịch chuyển {@code TU_DANH_GIA}. Mẫu {@code HO_SO_DANH_GIA_MO_TU_DANH_GIA} là suy luận.
 */
public class OutboxSetupNotices implements SetupNotices {

  static final String TEMPLATE = "HO_SO_DANH_GIA_MO_TU_DANH_GIA";

  private final OutboxWriter outbox;

  public OutboxSetupNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void selfAssessmentOpened(TenantId tenant, UUID profileId, UUID personId, long version) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(personId))
            .setTemplateCode(TEMPLATE)
            .setParams("{}")
            .setDeepLink(OutboxSelfAssessmentNotices.DEEP_LINK + profileId)
            .setPriority("BINH_THUONG")
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
