package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualNotices;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 MSG-10-02 cùng giao dịch lập hồ sơ năm (CORE-04-11): người thẩm định, người quyết định của
 * hồ sơ quý IV được báo cán bộ có hai quý liên tiếp KHTNV. Mã mẫu là suy luận; tham số không chứa
 * dữ liệu C2.
 */
public class OutboxAnnualNotices implements AnnualNotices {

  static final String TEMPLATE = "DE_XUAT_BO_TRI_LAI";
  static final String DEEP_LINK = "/appraisal/xep-loai-nam?ho-so=";

  private final OutboxWriter outbox;

  public OutboxAnnualNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void reassignFlagged(TenantId tenant, UUID annualProfileId, List<UUID> recipients) {
    if (recipients.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(recipients)
            .setTemplateCode(TEMPLATE)
            .setParams("{}")
            .setDeepLink(DEEP_LINK + annualProfileId)
            .setPriority("CAO")
            .setAggregateVersion(0)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            OutboxAppraisalStepEvents.AGGREGATE,
            annualProfileId,
            0L,
            payload,
            Classification.C1));
  }
}
