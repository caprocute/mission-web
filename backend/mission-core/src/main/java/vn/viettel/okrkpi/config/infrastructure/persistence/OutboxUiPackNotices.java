package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} cho vòng duyệt UI Pack (CORE-08-08), ghi {@code
 * config.outbox_event} trong giao dịch của bên gọi. Hai mẫu {@code UI_PACK_CHO_DUYET} và {@code
 * UI_PACK_BI_TU_CHOI} chưa có trong SVC-12 (suy luận, wiki trang 14).
 */
public class OutboxUiPackNotices implements UiPackNotices {

  static final String TEMPLATE_REVIEW = "UI_PACK_CHO_DUYET";
  static final String TEMPLATE_REJECTED = "UI_PACK_BI_TU_CHOI";
  static final String DEEP_LINK = "/config-studio/giao-dien";

  private final OutboxWriter outbox;

  public OutboxUiPackNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void reviewRequested(
      TenantId tenantId, String packName, UiPackVersion version, List<UUID> to) {
    send(
        tenantId,
        version,
        to,
        TEMPLATE_REVIEW,
        "{\"goi\":"
            + OutboxLockOrderNotices.quote(packName)
            + ",\"phienBan\":"
            + OutboxLockOrderNotices.quote(version.version())
            + "}",
        version.updatedBy());
  }

  @Override
  public void rejected(
      TenantId tenantId, String packName, UiPackVersion version, String comment, List<UUID> to) {
    send(
        tenantId,
        version,
        to,
        TEMPLATE_REJECTED,
        "{\"goi\":"
            + OutboxLockOrderNotices.quote(packName)
            + ",\"phienBan\":"
            + OutboxLockOrderNotices.quote(version.version())
            + ",\"yKien\":"
            + OutboxLockOrderNotices.quote(comment)
            + "}",
        version.updatedBy());
  }

  private void send(
      TenantId tenantId,
      UiPackVersion version,
      List<UUID> to,
      String template,
      String params,
      UUID actor) {
    if (to.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(to)
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink(DEEP_LINK)
            .setPriority("BINH_THUONG")
            .setAggregateVersion(version.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenantId,
                "ui_pack_version",
                version.id(),
                version.rowVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(actor)));
  }
}
