package vn.viettel.okrkpi.integ.infrastructure.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.integ.application.alert.AdminAlerts;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * Cảnh báo quản trị: metric {@code integ_anchor_shortfall_total}, {@code
 * integ_outbound_overdue_total} cho Prometheus và EVT-21 {@code notify.message.requested.v1} qua
 * {@code integ.outbox_event} tới danh sách quản trị cấu hình. Mẫu {@code
 * NEO_SO_CAI_THIEU_BIEN_NHAN} và {@code THONG_DIEP_RA_QUA_HAN} do SVC-12 dựng (suy luận, wiki trang
 * 14); tham số chỉ có mã cell, ngày, số đếm, mã kết nối và mã thông điệp (C1).
 */
public class OutboxAdminAlerts implements AdminAlerts {

  static final String TEMPLATE_SHORTFALL = "NEO_SO_CAI_THIEU_BIEN_NHAN";
  static final String TEMPLATE_OVERDUE = "THONG_DIEP_RA_QUA_HAN";

  private static final Logger LOG = LoggerFactory.getLogger(OutboxAdminAlerts.class);

  private final OutboxWriter outbox;
  private final MeterRegistry meters;
  private final List<UUID> recipients;
  private final ZoneId zone;
  private final ObjectMapper json;

  public OutboxAdminAlerts(
      OutboxWriter outbox,
      MeterRegistry meters,
      List<UUID> recipients,
      ZoneId zone,
      ObjectMapper json) {
    this.outbox = outbox;
    this.meters = meters;
    this.recipients = recipients == null ? List.of() : List.copyOf(recipients);
    this.zone = zone;
    this.json = json;
  }

  @Override
  public void anchorShortfall(
      TenantId tenant,
      UUID windowId,
      String cellCode,
      Instant windowEnd,
      int receipts,
      int required) {
    Counter.builder("integ_anchor_shortfall_total")
        .description("Ngày chưa đủ biên nhận bản neo")
        .tag("cell", cellCode)
        .register(meters)
        .increment();
    LOG.warn(
        "Ngày neo {} của cell {} chỉ có {}/{} biên nhận", windowEnd, cellCode, receipts, required);
    ObjectNode p = json.createObjectNode();
    p.put("cell", cellCode);
    p.put("ngay", windowEnd.atZone(zone).toLocalDate().minusDays(1).toString());
    p.put("bienNhan", receipts);
    p.put("toiThieu", required);
    notify(tenant, TEMPLATE_SHORTFALL, p, "integ.anchor_window", windowId);
  }

  @Override
  public void outboundOverdue(TenantId tenant, String connectorCode, UUID messageId) {
    Counter.builder("integ_outbound_overdue_total")
        .description("Thông điệp ra hết lượt thử lại chưa được xác nhận")
        .tag("connector", connectorCode)
        .register(meters)
        .increment();
    LOG.warn("Thông điệp ra {} tới {} quá hạn thử lại", messageId, connectorCode);
    ObjectNode p = json.createObjectNode();
    p.put("ketNoi", connectorCode);
    p.put("thongDiep", messageId.toString());
    notify(tenant, TEMPLATE_OVERDUE, p, "integ.outbound_message", messageId);
  }

  private void notify(
      TenantId tenant, String template, ObjectNode params, String aggregateType, UUID id) {
    if (recipients.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(recipients)
            .setTemplateCode(template)
            .setParams(params.toString())
            .setDeepLink(null)
            .setPriority("CAO")
            .setAggregateVersion(0)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            aggregateType,
            id,
            0,
            payload,
            Classification.C1));
  }
}
