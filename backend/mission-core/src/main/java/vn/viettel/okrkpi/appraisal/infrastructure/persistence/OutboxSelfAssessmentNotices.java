package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentNotices;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} báo người nộp phiếu tự đánh giá đã ký thành công
 * (MSG-09-04), ghi {@code appraisal.outbox_event} cùng giao dịch chuyển bước. Mẫu {@code
 * TU_DANH_GIA_DA_NOP} là suy luận (wiki trang 14).
 */
public class OutboxSelfAssessmentNotices implements SelfAssessmentNotices {

  static final String TEMPLATE = "TU_DANH_GIA_DA_NOP";
  static final String DEEP_LINK = "/appraisal/tu-danh-gia/";

  private final OutboxWriter outbox;

  public OutboxSelfAssessmentNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void submitted(
      TenantId tenant, UUID profileId, UUID personId, Instant signedAt, long version) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(personId))
            .setTemplateCode(TEMPLATE)
            .setParams("{\"kyLuc\":\"" + signedAt + "\"}")
            .setDeepLink(DEEP_LINK + profileId)
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
                Classification.C1)
            .withActor(ActorId.of(personId)));
  }
}
