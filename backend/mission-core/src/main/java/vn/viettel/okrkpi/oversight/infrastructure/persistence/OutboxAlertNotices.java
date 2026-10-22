package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.port.AlertNotices;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 tổng hợp cảnh báo mức Cao mới (MSG-14-01) qua {@code oversight.outbox_event}. Mẫu {@code
 * CANH_BAO_MUC_CAO_MOI} là suy luận (wiki trang 14); tham số chỉ có số cảnh báo, không có mã đối
 * tượng. Mã thực thể của sự kiện là UUID tất định của (lượt, người nhận).
 */
public class OutboxAlertNotices implements AlertNotices {

  static final String TEMPLATE = "CANH_BAO_MUC_CAO_MOI";
  static final String DEEP_LINK = "/oversight/alerts?severity=CAO&status=MOI";
  static final String AGGREGATE_TYPE = "AnomalyAlertDigest";

  private final OutboxWriter outbox;

  public OutboxAlertNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void newHighAlerts(TenantId tenant, UUID recipient, int count, UUID batchId) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(recipient))
            .setTemplateCode(TEMPLATE)
            .setParams("{\"soLuong\":" + count + "}")
            .setDeepLink(DEEP_LINK)
            .setPriority("CAO")
            .setAggregateVersion(1)
            .build();
    UUID aggregate =
        UUID.nameUUIDFromBytes((batchId + "|" + recipient).getBytes(StandardCharsets.UTF_8));
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            AGGREGATE_TYPE,
            aggregate,
            1,
            payload,
            Classification.C1));
  }
}
