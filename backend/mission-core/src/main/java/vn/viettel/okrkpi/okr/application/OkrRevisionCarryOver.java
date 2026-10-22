package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository.KeyResultCode;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.okr.domain.ProgressCalculator;

/**
 * CORE-01-12, QT-03 "Phiên bản mục tiêu mới": khi phiên bản điều chỉnh được duyệt, KR cùng mã của
 * phiên bản mới nhận giá trị hiện tại, mức tự tin, cờ rủi ro của KR phiên bản cũ và tính lại tiến
 * độ theo chỉ tiêu mới; KR mới thêm giữ giá trị đầu; không sao chép check-in. Chạy đồng bộ trong
 * giao dịch phê duyệt nên tiến độ không bao giờ bị đặt lại giữa chừng.
 */
public class OkrRevisionCarryOver {

  private final OkrCheckinRepository checkins;
  private final OkrCheckinDelegateRepository codes;

  public OkrRevisionCarryOver(OkrCheckinRepository checkins, OkrCheckinDelegateRepository codes) {
    this.checkins = Objects.requireNonNull(checkins, "checkins");
    this.codes = Objects.requireNonNull(codes, "codes");
  }

  @EventListener
  public void on(OkrObjectiveRevisionApproved event) {
    int bucket = event.periodBucket();
    Map<String, UUID> previous =
        codes.keyResultCodes(event.tenant(), bucket, event.previousId()).stream()
            .collect(Collectors.toMap(KeyResultCode::code, KeyResultCode::id, (a, b) -> a));
    boolean changed = false;
    for (KeyResultCode kr : codes.keyResultCodes(event.tenant(), bucket, event.revisionId())) {
      UUID oldId = previous.get(kr.code());
      if (oldId == null) {
        continue;
      }
      CheckinTarget old = checkins.target(event.tenant(), oldId).orElse(null);
      CheckinTarget now = checkins.target(event.tenant(), kr.id()).orElse(null);
      if (old == null || now == null || old.currentValue() == null) {
        continue;
      }
      BigDecimal progress =
          ProgressCalculator.keyResult(
              now.metricType(),
              now.direction(),
              now.baselineValue(),
              now.targetValue(),
              old.currentValue());
      changed |=
          checkins.writeKeyResultProgress(
              event.tenant(),
              bucket,
              kr.id(),
              now.rowVersion(),
              old.currentValue(),
              old.confidence(),
              progress,
              old.riskFlag(),
              event.actor());
    }
    if (changed) {
      checkins.writeObjectiveProgress(
          event.tenant(),
          bucket,
          event.revisionId(),
          ProgressCalculator.objective(
              checkins.objectiveWeights(event.tenant(), bucket, event.revisionId())),
          event.actor());
    }
  }
}
