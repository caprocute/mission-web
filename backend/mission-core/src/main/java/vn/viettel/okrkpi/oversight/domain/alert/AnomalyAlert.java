package vn.viettel.okrkpi.oversight.domain.alert;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Một dòng {@code oversight.anomaly_alert}. */
public record AnomalyAlert(
    UUID id,
    UUID periodId,
    int periodBucket,
    String signalCode,
    String severity,
    String subjectType,
    UUID subjectId,
    List<String> evidence,
    Instant detectedAt,
    UUID sourceEventId,
    String modelVersion,
    String status,
    long rowVersion) {

  public AnomalyAlert {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(periodId, "periodId");
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
  }

  public AnomalyAlert withStatus(String s) {
    return new AnomalyAlert(
        id,
        periodId,
        periodBucket,
        signalCode,
        severity,
        subjectType,
        subjectId,
        evidence,
        detectedAt,
        sourceEventId,
        modelVersion,
        s,
        rowVersion + 1);
  }
}
