package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTaskNotices;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} tới người lập kế hoạch (CORE-02-11): MSG-04-10 số
 * nhiệm vụ đã sinh, và cảnh báo khi dòng bỏ còn nhiệm vụ không huỷ được. Tham số mẫu chỉ có số đếm
 * và mã nhiệm vụ (C1).
 */
public class OutboxPlanTaskNotices implements PlanTaskNotices {

  static final String TEMPLATE_GENERATED = "KE_HOACH_DA_SINH_NHIEM_VU";
  static final String TEMPLATE_BLOCKED = "KE_HOACH_KHONG_HUY_DUOC_NHIEM_VU";

  private final OutboxWriter outbox;

  public OutboxPlanTaskNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void generated(
      TenantId tenant,
      UUID planId,
      UUID versionId,
      UUID planner,
      int created,
      int updated,
      int cancelled) {
    send(
        tenant,
        planId,
        versionId,
        planner,
        TEMPLATE_GENERATED,
        "{\"soMoi\":" + created + ",\"soCapNhat\":" + updated + ",\"soHuy\":" + cancelled + "}",
        "BINH_THUONG");
  }

  @Override
  public void cancelBlocked(
      TenantId tenant, UUID planId, UUID versionId, UUID planner, List<String> taskCodes) {
    send(
        tenant,
        planId,
        versionId,
        planner,
        TEMPLATE_BLOCKED,
        "{\"nhiemVu\":" + OutboxPlanNotices.quote(String.join(", ", taskCodes)) + "}",
        "CAO");
  }

  private void send(
      TenantId tenant,
      UUID planId,
      UUID versionId,
      UUID planner,
      String template,
      String params,
      String priority) {
    if (planner == null) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(planner))
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink("/ke-hoach/" + planId)
            .setPriority(priority)
            .setAggregateVersion(0)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            "plan_version",
            versionId,
            0,
            payload,
            Classification.C1));
  }
}
