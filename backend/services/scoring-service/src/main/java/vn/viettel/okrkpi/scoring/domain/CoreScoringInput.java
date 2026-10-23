package vn.viettel.okrkpi.scoring.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Một phần tử {@code AppraisalScoringInput} của {@code listAppraisalScoringInputs} (MOD-04,
 * CORE-04-06) đã đọc đủ việc lá qua {@code listAppraisalWorkItems}: hồ sơ, thuộc tính cấp hồ sơ và
 * việc lá của người trong kỳ (BS-03 DC-01). Bộ máy tính dựng {@link ScoringInput} từ đây theo
 * {@code inputs} của Policy Pack.
 *
 * @param incidents số sự việc có kết luận trong kỳ (BS-03 DC-05)
 */
public record CoreScoringInput(
    UUID profileId,
    UUID personId,
    String flow,
    UUID orgUnitId,
    UUID subjectGroupId,
    boolean head,
    boolean leadership,
    UUID policyPackVersionId,
    List<Criterion> generalCriteria,
    List<WorkItem> workItems,
    int incidents) {

  public CoreScoringInput {
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(flow, "flow");
    generalCriteria = List.copyOf(generalCriteria);
    workItems = List.copyOf(workItems);
  }

  /** Khoá phân giải Policy Pack ghim của hồ sơ (khối đối tượng, đơn vị). */
  public PolicyBindingKey bindingKey(UUID tenantId, UUID periodId) {
    return new PolicyBindingKey(tenantId, periodId, flow, subjectGroupId, orgUnitId);
  }

  /** Kết quả một tiêu chí chung. */
  public record Criterion(String code, boolean met) {}

  /**
   * Việc lá của hồ sơ ({@code WorkItem}).
   *
   * @param qualityLevel mức chất lượng nghiệm thu; {@code null} khi chưa nghiệm thu
   */
  public record WorkItem(
      UUID taskId,
      UUID parentTaskId,
      BigDecimal standardScore,
      Integer difficulty,
      String taskGroup,
      String axisCode,
      String statusAtLock,
      BigDecimal qualityLevel,
      int subjectiveLateWorkdays,
      int objectiveLateWorkdays,
      boolean exceeded,
      boolean outOfScope,
      boolean deferredByDecision) {

    public WorkItem {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(statusAtLock, "statusAtLock");
    }
  }
}
