package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.port.ReopenNotices;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} của yêu cầu mở lại kỳ, ghi {@code
 * oversight.outbox_event} cùng giao dịch: báo người được chỉ định có lượt cần duyệt và báo người
 * lập kết quả. Mẫu {@code MO_LAI_KY_CAN_DUYET}, {@code MO_LAI_KY_DA_DUYET}, {@code
 * MO_LAI_KY_BI_TU_CHOI} là suy luận (wiki trang 14). Tham số chỉ có mã yêu cầu và lượt.
 */
public class OutboxReopenNotices implements ReopenNotices {

  static final String TEMPLATE_PENDING = "MO_LAI_KY_CAN_DUYET";
  static final String TEMPLATE_APPROVED = "MO_LAI_KY_DA_DUYET";
  static final String TEMPLATE_REJECTED = "MO_LAI_KY_BI_TU_CHOI";
  static final String DEEP_LINK = "/oversight/mo-lai-ky/";

  private final OutboxWriter outbox;

  public OutboxReopenNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void approvalRequested(TenantId tenant, UUID assignee, ReopenRequest r, int round) {
    send(
        tenant,
        r,
        assignee,
        TEMPLATE_PENDING,
        "{\"ma\":\"" + r.code() + "\",\"luot\":" + round + "}",
        "CAO");
  }

  @Override
  public void decided(TenantId tenant, ReopenRequest r) {
    String template =
        switch (r.status()) {
          case TU_CHOI -> TEMPLATE_REJECTED;
          default -> TEMPLATE_APPROVED;
        };
    send(tenant, r, r.requestedBy(), template, "{\"ma\":\"" + r.code() + "\"}", "BINH_THUONG");
  }

  private void send(
      TenantId tenant,
      ReopenRequest r,
      UUID recipient,
      String template,
      String params,
      String priority) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(recipient))
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink(DEEP_LINK + r.id())
            .setPriority(priority)
            .setAggregateVersion(r.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            OutboxReopenEvents.AGGREGATE_TYPE,
            r.id(),
            r.rowVersion(),
            payload,
            Classification.C1));
  }
}
