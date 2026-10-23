package vn.viettel.okrkpi.oversight.infrastructure.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyAlertIntake;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AlertSignal;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.analytics.v1.DauHieuBatThuong;

/**
 * Consumer EVT-20 {@code analytics.anomaly.detected.v1} của MOD-07 (CORE-07-10). Gọi qua {@code
 * InboxMessageProcessor} của schema oversight: trùng mã sự kiện bị inbox bỏ qua, tạo cảnh báo và
 * hành động tự động cùng một giao dịch. Thân sai hợp đồng (mã dấu hiệu, mức, chuỗi bằng chứng không
 * phải mảng JSON) đi thẳng thư chết.
 */
public class AnomalyDetectedListener {

  /** Định danh consumer trong {@code oversight.inbox_processed}. */
  public static final String CONSUMER = "oversight.anomaly-detected";

  private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};

  private final AnomalyAlertIntake intake;
  private final ObjectMapper json;

  public AnomalyDetectedListener(AnomalyAlertIntake intake, ObjectMapper json) {
    this.intake = Objects.requireNonNull(intake, "intake");
    this.json = Objects.requireNonNull(json, "json");
  }

  public void on(DauHieuBatThuong event, InboxEnvelope<?> envelope) {
    List<String> evidence;
    try {
      evidence =
          json.readValue(event.getEvidenceChain(), LIST).stream()
              .map(x -> x == null ? null : x.toString())
              .toList();
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new PermanentInboxException("EVT-20: chuỗi bằng chứng không phải mảng JSON");
    }
    Instant at = envelope.occurredAt() == null ? Instant.now() : envelope.occurredAt();
    try {
      intake.receive(
          envelope.tenantId(),
          new AlertSignal(
              envelope.eventId(),
              at,
              event.getAlertKey(),
              event.getSignalCode(),
              event.getSeverity(),
              event.getSubjectType(),
              event.getSubjectId(),
              evidence,
              event.getModelVersion()));
    } catch (AlertRules.InvalidSignal e) {
      throw new PermanentInboxException("EVT-20: " + e.getMessage());
    }
  }
}
