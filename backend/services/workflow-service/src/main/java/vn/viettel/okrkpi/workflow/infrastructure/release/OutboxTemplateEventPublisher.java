package vn.viettel.okrkpi.workflow.infrastructure.release;

import java.nio.ByteBuffer;
import java.util.List;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.workflow.application.port.TemplateEventPublisher;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;
import vn.vtokr.config.v1.MauLuongDaPhatHanh;

/**
 * Phát "mẫu luồng đã phát hành" qua {@code flowable.outbox_event} trong cùng giao dịch ghi {@code
 * HIEU_LUC} (BS-01). Hợp đồng ghi tên {@code config.wf_template.published.v1}, nhưng quy ước tên
 * topic của baseline và ràng buộc của outbox không cho dấu gạch dưới, nên dùng {@code
 * config.wftemplate.published.v1} như tiền lệ {@code config.uipack.published.v1}; lược đồ Avro đề
 * xuất đặt trong dịch vụ vì {@code contracts/events} chưa có (wiki trang 14).
 */
public class OutboxTemplateEventPublisher implements TemplateEventPublisher {

  /** Topic của sự kiện. */
  public static final String TOPIC = "config.wftemplate.published.v1";

  static final String AGGREGATE = "WF_TEMPLATE";

  private final OutboxWriter outbox;

  public OutboxTemplateEventPublisher(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void published(TemplateVersion t, List<String> cells) {
    MauLuongDaPhatHanh payload =
        MauLuongDaPhatHanh.newBuilder()
            .setTemplateId(t.id())
            .setTemplateCode(t.templateCode())
            .setVersionNo(t.versionNo())
            .setBpmnSha256(ByteBuffer.wrap(t.bpmnSha256()))
            .setSignature(ByteBuffer.wrap(t.signature()))
            .setApprovedBy(t.approvedBy())
            .setCellCodes(List.copyOf(cells))
            .setAggregateVersion((int) t.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                TOPIC,
                TenantId.of(t.tenantId()),
                AGGREGATE,
                t.id(),
                t.rowVersion(),
                payload,
                Classification.C0)
            .withActor(ActorId.of(t.approvedBy())));
  }
}
