package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.SecurityAlerts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} cho quản trị tenant khi có báo truy cập bất thường,
 * ghi vào {@code config.outbox_event} trong giao dịch của bên gọi. Tham số mẫu chỉ có giờ báo (C1),
 * không có tên hay thông tin liên hệ của người báo.
 */
public class OutboxSecurityAlerts implements SecurityAlerts {

  static final String TEMPLATE = "BAO_TRUY_CAP_BAT_THUONG";
  static final String DEEP_LINK = "/quan-tri/nguoi-dung";
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

  private final OutboxWriter outbox;

  public OutboxSecurityAlerts(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void suspiciousAccessReported(
      TenantId tenant, UUID reportId, ActorId reporter, List<UUID> admins, Instant reportedAt) {
    if (admins.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(admins)
            .setTemplateCode(TEMPLATE)
            .setParams("{\"gio\":\"" + CLOCK.format(reportedAt) + "\"}")
            .setDeepLink(DEEP_LINK)
            .setPriority("CAO")
            .setAggregateVersion(0)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                "suspicious_access_report",
                reportId,
                0,
                payload,
                Classification.C1)
            .withActor(reporter));
  }
}
