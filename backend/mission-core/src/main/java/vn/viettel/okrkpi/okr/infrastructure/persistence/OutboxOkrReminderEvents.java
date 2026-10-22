package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult;
import vn.viettel.okrkpi.okr.application.port.OkrReminderEvents;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 của nhắc check-in OKR (CORE-01-11) vào {@code okr.outbox_event}; tham số chỉ có mã KR và
 * ngày (C1). Sự kiện do hệ thống phát nên không gắn người gọi.
 */
public class OutboxOkrReminderEvents implements OkrReminderEvents {

  static final String AGGREGATE = "key_result";

  /** Mã mẫu nhắc check-in ở SVC-12 (suy luận tên mã). */
  static final String TEMPLATE_DUE = "OKR_NHAC_CHECK_IN";

  /** Mã mẫu báo người đứng đầu KR quá hạn check-in (BR-03-08, suy luận tên mã). */
  static final String TEMPLATE_OVERDUE = "OKR_QUA_HAN_CHECK_IN";

  private final OutboxWriter outbox;

  public OutboxOkrReminderEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void checkinDue(TenantId tenant, DueKeyResult kr, List<UUID> recipients, LocalDate end) {
    append(
        tenant,
        kr,
        recipients,
        TEMPLATE_DUE,
        "{\"ma\":" + OutboxOkrCheckinEvents.quote(kr.code()) + ",\"han\":\"" + end + "\"}",
        "THUONG");
  }

  @Override
  public void checkinOverdue(TenantId tenant, DueKeyResult kr, List<UUID> recipients, int cycles) {
    append(
        tenant,
        kr,
        recipients,
        TEMPLATE_OVERDUE,
        "{\"ma\":" + OutboxOkrCheckinEvents.quote(kr.code()) + ",\"soChuKy\":" + cycles + "}",
        "CAO");
  }

  private void append(
      TenantId tenant,
      DueKeyResult kr,
      List<UUID> recipients,
      String template,
      String params,
      String priority) {
    if (recipients.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(recipients))
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink("/okr/checkin?kr=" + kr.id())
            .setPriority(priority)
            .setAggregateVersion((int) kr.rowVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            AGGREGATE,
            kr.id(),
            kr.rowVersion(),
            payload,
            Classification.C1));
  }
}
