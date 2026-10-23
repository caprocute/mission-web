package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.port.ComplaintEvents;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.oversight.v1.KhieuNaiDaGui;

/**
 * EVT-25 {@code oversight.complaint.filed.v1} và EVT-21 của khiếu nại vào {@code
 * oversight.outbox_event} cùng giao dịch chuyển {@code MOI}; aggregate {@code Complaint}, phiên bản
 * là {@code row_version} mới. EVT-25 không mang nội dung C2. Mẫu {@code KHIEU_NAI_DA_GUI}
 * (MSG-12-02) và {@code KHIEU_NAI_KHONG_GUI_DUOC} là suy luận (wiki trang 14); tham số chỉ có mã
 * khiếu nại.
 */
public class OutboxComplaintEvents implements ComplaintEvents {

  private static final ObjectMapper JSON = new ObjectMapper();

  static final String AGGREGATE_TYPE = "Complaint";
  static final String TEMPLATE_FILED = "KHIEU_NAI_DA_GUI";
  static final String TEMPLATE_NOT_FILED = "KHIEU_NAI_KHONG_GUI_DUOC";
  static final String DEEP_LINK = "/oversight/khieu-nai/";

  private final OutboxWriter outbox;

  public OutboxComplaintEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void filed(TenantId tenant, Complaint c) {
    KhieuNaiDaGui payload =
        KhieuNaiDaGui.newBuilder()
            .setComplaintId(c.id())
            .setProfileId(c.profileId())
            .setTargetStage(c.targetStage().name())
            .setComplainantId(c.complainantId())
            .setFiledAt(c.filedAt())
            .setResponseDeadline(c.responseDeadline())
            .setAggregateVersion(c.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_25_KHIEU_NAI_DA_GUI,
            tenant,
            AGGREGATE_TYPE,
            c.id(),
            c.rowVersion(),
            payload,
            Classification.C1));
    notify(tenant, c, TEMPLATE_FILED);
  }

  @Override
  public void notFiled(TenantId tenant, Complaint c) {
    notify(tenant, c, TEMPLATE_NOT_FILED);
  }

  @Override
  public void stateChanged(
      TenantId tenant, Complaint c, String template, Collection<UUID> recipients) {
    if (recipients.isEmpty()) {
      return;
    }
    notify(tenant, c, template, List.copyOf(new LinkedHashSet<>(recipients)));
  }

  @Override
  public void stateChanged(
      TenantId tenant,
      Complaint c,
      String template,
      Collection<UUID> recipients,
      Map<String, String> params) {
    if (recipients.isEmpty()) {
      return;
    }
    notify(tenant, c, template, List.copyOf(new LinkedHashSet<>(recipients)), params);
  }

  private void notify(TenantId tenant, Complaint c, String template) {
    notify(tenant, c, template, List.of(c.complainantId()));
  }

  private void notify(TenantId tenant, Complaint c, String template, List<UUID> recipients) {
    notify(tenant, c, template, recipients, Map.of());
  }

  private void notify(
      TenantId tenant,
      Complaint c,
      String template,
      List<UUID> recipients,
      Map<String, String> params) {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("ma", c.code());
    all.putAll(params);
    String json;
    try {
      json = JSON.writeValueAsString(all);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(recipients)
            .setTemplateCode(template)
            .setParams(json)
            .setDeepLink(DEEP_LINK + c.id())
            .setPriority("BINH_THUONG")
            .setAggregateVersion(c.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            AGGREGATE_TYPE,
            c.id(),
            c.rowVersion(),
            payload,
            Classification.C1));
  }
}
