package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.product.v1.MinhChungDaNop;
import vn.vtokr.product.v1.MinhChungDaXacNhan;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * EVT-07 {@code product.evidence.submitted.v1}, EVT-08 {@code product.evidence.accepted.v1} và
 * EVT-21 thông báo, ghi vào {@code product.outbox_event} cùng giao dịch với phiên bản (CORE-03-03,
 * CORE-03-06, CORE-03-07).
 */
public class OutboxEvidenceEvents implements EvidenceEvents {

  static final String AGGREGATE = "evidence_version";
  static final String NOTICE_AGGREGATE = "evidence";
  static final String TEMPLATE_REVIEW = "MINH_CHUNG_CHO_NGHIEM_THU";
  static final String TEMPLATE_INTEGRITY = "MINH_CHUNG_MAT_TOAN_VEN";
  static final String TEMPLATE_REVISION = "MINH_CHUNG_CAN_BO_SUNG";
  static final String TEMPLATE_REJECTED = "MINH_CHUNG_BI_TU_CHOI";
  static final String AUDIT_AI_CONFIRMED = "XAC_NHAN_O_AI";

  private final OutboxWriter outbox;

  public OutboxEvidenceEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  /**
   * Ghi sổ cái việc xác nhận ô AI qua {@code sys.audit.v1} (SVC-09 tiêu thụ): {@code purpose} mang
   * mã có cấu trúc {@code GIU=<ô,...>;SUA=<ô,...>;GOI_Y=<mã gợi ý>}, không mang giá trị của ô (suy
   * luận: lược đồ AuditEvent chưa có trường riêng cho danh sách ô).
   */
  @Override
  public void aiFieldsConfirmed(AiFieldsConfirmed e) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(AUDIT_AI_CONFIRMED)
            .setObjectType("product." + AGGREGATE)
            .setObjectId(e.versionId().toString())
            .setOutcome("THANH_CONG")
            .setPurpose(
                "GIU="
                    + String.join(",", e.kept())
                    + ";SUA="
                    + String.join(",", e.edited())
                    + ";GOI_Y="
                    + e.aiSuggestionId())
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(false)
            .setTargets(null)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.SYS_AUDIT_V1,
                e.tenantId(),
                AGGREGATE,
                e.versionId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.actorId())));
  }

  @Override
  public void submitted(Submitted e) {
    MinhChungDaNop payload =
        MinhChungDaNop.newBuilder()
            .setEvidenceId(e.evidenceId())
            .setVersionId(e.versionId())
            .setDeliverableId(e.deliverableId())
            .setTaskId(e.taskId())
            .setObjectSha256(ByteBuffer.wrap(e.objectSha256()))
            .setAiConfirmed(e.aiConfirmed())
            .setSubmittedBy(e.submittedBy())
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_07_MINH_CHUNG_DA_NOP,
                e.tenantId(),
                AGGREGATE,
                e.versionId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.submittedBy())));
  }

  @Override
  public void reviewRequested(
      TenantId tenantId,
      UUID reviewerId,
      String taskCode,
      UUID evidenceId,
      int versionNo,
      UUID actorId,
      long aggregateVersion) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(reviewerId))
            .setTemplateCode(TEMPLATE_REVIEW)
            .setParams("{\"maNhiemVu\":" + quote(taskCode) + ",\"phienBan\":" + versionNo + "}")
            .setDeepLink("/evidence/" + evidenceId + "/review")
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) aggregateVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenantId,
                NOTICE_AGGREGATE,
                evidenceId,
                aggregateVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }

  @Override
  public void integrityFailed(
      TenantId tenantId,
      List<UUID> recipientIds,
      String taskCode,
      UUID evidenceId,
      int versionNo,
      UUID actorId,
      long aggregateVersion) {
    if (recipientIds.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(recipientIds))
            .setTemplateCode(TEMPLATE_INTEGRITY)
            .setParams("{\"maNhiemVu\":" + quote(taskCode) + ",\"phienBan\":" + versionNo + "}")
            .setDeepLink("/evidence/" + evidenceId + "/review")
            .setPriority("CAO")
            .setAggregateVersion((int) aggregateVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenantId,
                NOTICE_AGGREGATE,
                evidenceId,
                aggregateVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }

  @Override
  public void accepted(Accepted e) {
    MinhChungDaXacNhan payload =
        MinhChungDaXacNhan.newBuilder()
            .setEvidenceId(e.evidenceId())
            .setVersionId(e.versionId())
            .setTaskId(e.taskId())
            .setQualityLevel(e.qualityLevel().setScale(2, RoundingMode.UNNECESSARY))
            .setAcceptedBy(e.acceptedBy())
            .setAcceptedAt(e.acceptedAt())
            .setAggregateVersion((int) e.aggregateVersion())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_08_MINH_CHUNG_DA_XAC_NHAN,
                e.tenantId(),
                AGGREGATE,
                e.versionId(),
                e.aggregateVersion(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(e.acceptedBy())));
  }

  @Override
  public void returned(
      TenantId tenantId,
      UUID submitterId,
      boolean rejected,
      String taskCode,
      UUID evidenceId,
      int versionNo,
      UUID actorId,
      long aggregateVersion) {
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(submitterId))
            .setTemplateCode(rejected ? TEMPLATE_REJECTED : TEMPLATE_REVISION)
            .setParams("{\"maNhiemVu\":" + quote(taskCode) + ",\"phienBan\":" + versionNo + "}")
            .setDeepLink("/evidence/" + evidenceId)
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) aggregateVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenantId,
                NOTICE_AGGREGATE,
                evidenceId,
                aggregateVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actorId)));
  }

  private static String quote(String s) {
    if (s == null) {
      return "null";
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
