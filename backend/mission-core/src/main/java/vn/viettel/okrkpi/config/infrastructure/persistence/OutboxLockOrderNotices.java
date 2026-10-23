package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} cho lệnh khoá kỳ và cho huỷ, bù trừ (MSG-08-05,
 * MSG-08-08), ghi {@code config.outbox_event} trong giao dịch của bên gọi. Tham số mẫu chỉ có tên
 * kỳ, mã người duyệt và giờ (C1); tên người duyệt do SVC-12 tra theo mã khi ghép MSG-08-01.
 */
public class OutboxLockOrderNotices implements LockOrderNotices {

  static final String TEMPLATE_REQUESTED = "LENH_KHOA_KY_CHO_DUYET";
  static final String TEMPLATE_APPROVED = "LENH_KHOA_KY_DA_DUYET";
  static final String TEMPLATE_CANCEL_REQUESTED = "KHOA_KY_DE_NGHI_HUY";
  static final String TEMPLATE_CANCELLED = "KHOA_KY_DA_BU_TRU";
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

  private final OutboxWriter outbox;

  public OutboxLockOrderNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void requested(PeriodLockOrder order, String periodName, List<UUID> recipients) {
    send(
        order,
        recipients,
        TEMPLATE_REQUESTED,
        "{\"ky\":" + quote(periodName) + ",\"canCu\":" + quote(order.basisDoc()) + "}",
        order.requestedBy());
  }

  @Override
  public void approved(PeriodLockOrder order, String periodName, List<UUID> recipients) {
    String from = order.plannedLockAt() == null ? "" : CLOCK.format(order.plannedLockAt());
    send(
        order,
        recipients,
        TEMPLATE_APPROVED,
        "{\"ky\":"
            + quote(periodName)
            + ",\"nguoiDuyetId\":"
            + quote(order.approvedBy().toString())
            + ",\"thoiDiem\":"
            + quote(from)
            + "}",
        order.approvedBy());
  }

  /** MSG-08-05: đề nghị huỷ và bù trừ chờ người duyệt thứ hai. */
  @Override
  public void cancelRequested(PeriodLockOrder order, String periodName, List<UUID> recipients) {
    send(
        order,
        recipients,
        TEMPLATE_CANCEL_REQUESTED,
        "{\"ky\":" + quote(periodName) + "}",
        order.cancelRequest().requestedBy());
  }

  /** MSG-08-08: saga đã bù trừ toàn bộ, kỳ trở về trạng thái mở. */
  @Override
  public void cancelCompleted(PeriodLockOrder order, String periodName, List<UUID> recipients) {
    send(
        order,
        recipients,
        TEMPLATE_CANCELLED,
        "{\"ky\":" + quote(periodName) + "}",
        order.cancelRequest().approvedBy());
  }

  private void send(
      PeriodLockOrder order, List<UUID> recipients, String template, String params, UUID actor) {
    if (recipients.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(recipients)
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink("/cau-hinh/ky-danh-gia/" + order.periodId().value() + "/khoa-ky")
            .setPriority("CAO")
            .setAggregateVersion(order.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                order.tenantId(),
                "period_lock_order",
                order.id(),
                order.rowVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(actor)));
  }

  static String quote(String s) {
    if (s == null) {
      return "\"\"";
    }
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
