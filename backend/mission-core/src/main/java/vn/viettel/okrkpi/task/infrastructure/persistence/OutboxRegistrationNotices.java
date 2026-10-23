package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.assign.port.RegistrationNotices;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} của đăng ký nhiệm vụ (CORE-02-17). Tham số mẫu chỉ có
 * mã nhiệm vụ (C1); lý do từ chối người nhận đọc trong chi tiết nhiệm vụ.
 */
public class OutboxRegistrationNotices implements RegistrationNotices {

  static final String TEMPLATE_SUBMITTED = "NHIEM_VU_DANG_KY_CHO_DUYET";
  static final String TEMPLATE_REJECTED = "NHIEM_VU_DANG_KY_BI_TU_CHOI";
  static final String TEMPLATE_WAITING = "NHIEM_VU_DANG_KY_CHO_DIEU_CHINH_KE_HOACH";

  private final OutboxWriter outbox;

  public OutboxRegistrationNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void submitted(TenantId tenant, UUID taskId, String code, List<UUID> managers) {
    send(tenant, taskId, code, managers, TEMPLATE_SUBMITTED, "BINH_THUONG");
  }

  @Override
  public void rejected(TenantId tenant, UUID taskId, String code, UUID registrant) {
    send(
        tenant,
        taskId,
        code,
        registrant == null ? List.of() : List.of(registrant),
        TEMPLATE_REJECTED,
        "BINH_THUONG");
  }

  @Override
  public void waitingForAdjustment(TenantId tenant, UUID taskId, String code, List<UUID> planners) {
    send(tenant, taskId, code, planners, TEMPLATE_WAITING, "BINH_THUONG");
  }

  private void send(
      TenantId tenant, UUID taskId, String code, List<UUID> to, String template, String priority) {
    if (to.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(to))
            .setTemplateCode(template)
            .setParams("{\"ma\":" + OutboxPlanNotices.quote(code) + "}")
            .setDeepLink("/nhiem-vu/" + taskId)
            .setPriority(priority)
            .setAggregateVersion(0)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            "task",
            taskId,
            0,
            payload,
            Classification.C1));
  }
}
