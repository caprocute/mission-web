package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovalNotices;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} cho vòng phê duyệt phát hành Policy Pack (CORE-05-09),
 * ghi {@code policy.outbox_event} trong giao dịch của bên gọi. Hai mẫu {@code POLICY_CHO_PHE_DUYET}
 * và {@code POLICY_BI_TU_CHOI} chưa có trong SVC-12 (suy luận, wiki trang 14).
 */
public class OutboxPolicyApprovalNotices implements PolicyApprovalNotices {

  static final String TEMPLATE_SUBMITTED = "POLICY_CHO_PHE_DUYET";
  static final String TEMPLATE_REJECTED = "POLICY_BI_TU_CHOI";

  /** Tab kiểm thử và phê duyệt của SCR-1503 trong fe-config-studio. */
  static String deepLink(PolicyPackVersion v) {
    return "/config-studio/goi/" + v.packId() + "/" + v.version() + "/test-case";
  }

  private final OutboxWriter outbox;
  private final ObjectMapper json;

  public OutboxPolicyApprovalNotices(OutboxWriter outbox, ObjectMapper json) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void submitted(
      TenantId tenant, String packName, PolicyPackVersion version, List<UUID> to) {
    send(tenant, version, to, TEMPLATE_SUBMITTED, params(packName, version, null));
  }

  @Override
  public void rejected(
      TenantId tenant, String packName, PolicyPackVersion version, String reason, List<UUID> to) {
    send(tenant, version, to, TEMPLATE_REJECTED, params(packName, version, reason));
  }

  private String params(String packName, PolicyPackVersion version, String reason) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("goi", packName);
    p.put("phienBan", version.version());
    if (reason != null) {
      p.put("yKien", reason);
    }
    try {
      return json.writeValueAsString(p);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được tham số thông báo", e);
    }
  }

  private void send(
      TenantId tenant, PolicyPackVersion version, List<UUID> to, String template, String params) {
    if (to.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(to)
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink(deepLink(version))
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) version.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                "policy_pack_version",
                version.id(),
                version.rowVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(version.updatedBy())));
  }
}
