package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.plan.port.PlanNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} nhắc hạn duyệt (CORE-02-07) và báo trả lại kế hoạch
 * (CORE-02-08), ghi {@code task.outbox_event}. Tham số mẫu chỉ có tên kỳ và ngày hạn (C1);
 * MSG-04-08 do SVC-12 ghép theo mã mẫu {@link #TEMPLATE_OVERDUE}.
 */
public class OutboxPlanNotices implements PlanNotices {

  static final String TEMPLATE_REMIND = "KE_HOACH_SAP_HET_HAN_DUYET";
  static final String TEMPLATE_OVERDUE = "KE_HOACH_QUA_HAN_DUYET";
  static final String TEMPLATE_RETURNED = "KE_HOACH_BI_TRA_LAI";
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final OutboxWriter outbox;

  public OutboxPlanNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void remind(
      TenantId tenant, PendingPlan plan, String periodName, LocalDate deadline, List<UUID> to) {
    send(tenant, plan, periodName, deadline, to, TEMPLATE_REMIND, "BINH_THUONG");
  }

  @Override
  public void overdue(
      TenantId tenant, PendingPlan plan, String periodName, LocalDate deadline, List<UUID> to) {
    send(tenant, plan, periodName, deadline, to, TEMPLATE_OVERDUE, "CAO");
  }

  /** Ý kiến người duyệt không vào tham số mẫu; người nhận mở kế hoạch để đọc (suy luận). */
  @Override
  public void returned(TenantId tenant, PendingPlan plan, String periodName, UUID author) {
    send(
        tenant,
        plan,
        "{\"ky\":" + quote(periodName) + "}",
        List.of(author),
        TEMPLATE_RETURNED,
        "CAO");
  }

  private void send(
      TenantId tenant,
      PendingPlan plan,
      String periodName,
      LocalDate deadline,
      List<UUID> to,
      String template,
      String priority) {
    send(
        tenant,
        plan,
        "{\"ky\":" + quote(periodName) + ",\"ngay\":" + quote(DAY.format(deadline)) + "}",
        to,
        template,
        priority);
  }

  private void send(
      TenantId tenant,
      PendingPlan plan,
      String params,
      List<UUID> to,
      String template,
      String priority) {
    if (to.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(to)
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink("/ke-hoach/" + plan.planId())
            .setPriority(priority)
            .setAggregateVersion((int) plan.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            "plan_version",
            plan.versionId(),
            plan.rowVersion(),
            payload,
            Classification.C1));
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
