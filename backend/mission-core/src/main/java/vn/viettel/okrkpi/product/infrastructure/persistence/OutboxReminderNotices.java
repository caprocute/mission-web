package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderNotices;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 nhắc quá hạn nghiệm thu (BR-07-12, MSG-07-14) vào {@code product.outbox_event}. Tổng thể
 * là "lời nhắc của một người nhận": mã tổng thể là UUID tên của (người nhận, loại), phiên bản là số
 * ngày epoch, nên consumer bỏ được bản cũ hay trùng mà không cần bảng riêng.
 */
public class OutboxReminderNotices implements ReminderNotices {

  static final String AGGREGATE = "evidence_reminder";
  static final String TEMPLATE_REVIEWER = "MINH_CHUNG_QUA_HAN_NGHIEM_THU";
  static final String TEMPLATE_SUMMARY = "TOM_TAT_QUA_HAN_NGHIEM_THU";
  static final String DEEP_LINK = "/evidence?reviewer=me";

  private final OutboxWriter outbox;

  public OutboxReminderNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void overdueForReviewer(
      TenantId tenant, UUID reviewerId, int count, int days, LocalDate day) {
    append(
        tenant,
        reviewerId,
        TEMPLATE_REVIEWER,
        "{\"n\":" + count + ",\"k\":" + days + "}",
        DEEP_LINK,
        day);
  }

  @Override
  public void overdueSummary(
      TenantId tenant, UUID supervisorId, int count, int reviewers, int days, LocalDate day) {
    append(
        tenant,
        supervisorId,
        TEMPLATE_SUMMARY,
        "{\"n\":" + count + ",\"k\":" + days + ",\"soNguoi\":" + reviewers + "}",
        "/evidence",
        day);
  }

  private void append(
      TenantId tenant, UUID recipient, String template, String params, String link, LocalDate day) {
    long version = day.toEpochDay();
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(recipient))
            .setTemplateCode(template)
            .setParams(params)
            .setDeepLink(link)
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            AGGREGATE,
            aggregateId(recipient, template),
            version,
            payload,
            Classification.C1));
  }

  static UUID aggregateId(UUID recipient, String template) {
    return UUID.nameUUIDFromBytes((recipient + ":" + template).getBytes(StandardCharsets.UTF_8));
  }
}
