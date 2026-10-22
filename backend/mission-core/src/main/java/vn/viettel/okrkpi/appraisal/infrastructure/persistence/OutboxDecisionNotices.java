package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.decision.port.DecisionNotices;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-12 {@code appraisal.rating.decided.v1} và EVT-21 của bước quyết định (CORE-04-08), ghi {@code
 * appraisal.outbox_event} cùng giao dịch nhận EVT-18. Mẫu {@code HO_SO_DANH_GIA_DA_QUYET_DINH}
 * (MSG-09-11) và {@code QUYET_DINH_LO_HOAN_TAT} (MSG-09-16) là suy luận; tham số chỉ có kỳ, mức,
 * hạn khiếu nại, số quyết định, không chứa C2.
 */
public class OutboxDecisionNotices implements DecisionNotices {

  static final String DECIDED_TEMPLATE = "HO_SO_DANH_GIA_DA_QUYET_DINH";
  static final String BATCH_TEMPLATE = "QUYET_DINH_LO_HOAN_TAT";
  static final String BATCH_AGGREGATE = "batch_decision";
  static final String BATCH_LINK = "/appraisal/quyet-dinh?lo=";
  static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  static final Map<RatingLevel, String> LABELS =
      Map.of(
          RatingLevel.HTXSNV, "Hoàn thành xuất sắc nhiệm vụ",
          RatingLevel.HTTNV, "Hoàn thành tốt nhiệm vụ",
          RatingLevel.HTNV, "Hoàn thành nhiệm vụ",
          RatingLevel.KHTNV, "Không hoàn thành nhiệm vụ");
  private static final String[] QUARTERS = {"I", "II", "III", "IV"};

  private final OutboxWriter outbox;

  public OutboxDecisionNotices(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void decided(
      TenantId tenant,
      ProfileRow row,
      RatingLevel level,
      UUID deciderId,
      UUID signRequestId,
      LocalDate complaintDeadline,
      long version) {
    XepLoaiDaQuyetDinh payload =
        XepLoaiDaQuyetDinh.newBuilder()
            .setProfileId(row.id())
            .setPersonId(row.personId())
            .setFlow(row.flow())
            .setRatingCode(level.name())
            .setScoreSnapshotId(row.currentSnapshotId())
            .setDeciderId(deciderId)
            .setSignRequestId(signRequestId)
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH,
                tenant,
                OutboxAppraisalStepEvents.AGGREGATE,
                row.id(),
                version,
                payload,
                Classification.C1)
            .withActor(ActorId.of(deciderId)));
    String params =
        "{\"ky\":\""
            + period(row.periodBucket())
            + "\",\"muc\":\""
            + LABELS.get(level)
            + "\",\"han\":\""
            + complaintDeadline.format(DATE)
            + "\"}";
    YeuCauThongBao notice =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(row.personId()))
            .setTemplateCode(DECIDED_TEMPLATE)
            .setParams(params)
            .setDeepLink(OutboxSelfAssessmentNotices.DEEP_LINK + row.id())
            .setPriority("CAO")
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            OutboxAppraisalStepEvents.AGGREGATE,
            row.id(),
            version,
            notice,
            Classification.C1));
  }

  @Override
  public void batchCompleted(
      TenantId tenant, UUID batchId, UUID deciderId, String decisionNo, int total, long version) {
    YeuCauThongBao notice =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.of(deciderId))
            .setTemplateCode(BATCH_TEMPLATE)
            .setParams("{\"so_qd\":\"" + escape(decisionNo) + "\",\"tong\":" + total + "}")
            .setDeepLink(BATCH_LINK + batchId)
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
            EventTopics.EVT_21_YEU_CAU_THONG_BAO,
            tenant,
            BATCH_AGGREGATE,
            batchId,
            version,
            notice,
            Classification.C1));
  }

  /** Nhãn kỳ quý từ khoá phân vùng {@code YYYYQ}, ví dụ "Quý IV/2026". */
  static String period(int bucket) {
    int q = bucket % 10;
    return q >= 1 && q <= 4 ? "Quý " + QUARTERS[q - 1] + "/" + bucket / 10 : "năm " + bucket / 10;
  }

  static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
