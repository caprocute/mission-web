package vn.viettel.okrkpi.scoring.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Vết tính của kết quả {@code OK} mới nhất của một hồ sơ trong một luồng (nguồn của {@code
 * getAppraisalScoreTrace}, SCR-0901 ngăn "Vết tính", SCR-0903).
 */
public record ScoreTrace(
    UUID resultId,
    UUID tenantId,
    UUID profileId,
    UUID personId,
    String flow,
    String policyVersion,
    String engineVersion,
    List<TraceStep> steps) {

  public ScoreTrace {
    Objects.requireNonNull(resultId, "resultId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(flow, "flow");
    steps = List.copyOf(steps);
  }
}
