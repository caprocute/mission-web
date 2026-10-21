package vn.viettel.okrkpi.org.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Quy tắc nghiệp vụ phân công, kiêm nhiệm, điều động và xác định đơn vị đánh giá
 * ({@code BR-01-08}, {@code BR-01-09}).
 */
public final class AssignmentRules {

  public static final Set<String> ALLOWED_KINDS =
      Set.of("CHINH", "KIEM_NHIEM", "DIEU_DONG", "BIET_PHAI", "TAM_GIU");
  public static final Set<String> ALLOWED_BRANCHES = Set.of("DANG", "CHINH_QUYEN");

  private AssignmentRules() {
  }

  /**
   * Kết quả lập kế hoạch ghi nhận phân công mới hoặc điều động:
   * <ul>
   *   <li>{@code versionPlan}: kế hoạch đóng các {@code version_id} cũ và chèn các phiên bản mới.</li>
   *   <li>{@code changedAssignmentIds}: danh sách mã phân công bị ảnh hưởng để phát sự kiện {@code EVT-15}.</li>
   * </ul>
   */
  public record AssignmentPlan(
      VersionPlan<AssignmentVersion> versionPlan,
      List<UUID> changedAssignmentIds) {
  }

  /**
   * Kết quả xác định đơn vị thực hiện đánh giá cuối kỳ theo {@code BR-01-09}.
   *
   * @param evaluatingUnitId đơn vị chủ trì đánh giá (đơn vị mới nếu công tác {@code >= 2} tháng trong kỳ; đơn vị cũ nếu {@code < 2} tháng)
   * @param currentUnitId đơn vị công tác tại thời điểm cuối kỳ
   * @param previousUnitId đơn vị chuyển đi trong kỳ (hoặc {@code null} nếu không điều động giữa kỳ)
   * @param transferredMidPeriod {@code true} nếu có điều động giữa kỳ đánh giá
   * @param transferredUnderTwoMonths {@code true} nếu thời gian tại đơn vị mới trong kỳ {@code < 2} tháng (đơn vị cũ đánh giá, đơn vị mới nhận xét phối hợp)
   * @param daysAtNewUnit số ngày làm việc/công tác tại đơn vị mới trong kỳ
   */
  public record EvaluationUnitResolution(
      UUID evaluatingUnitId,
      UUID currentUnitId,
      UUID previousUnitId,
      boolean transferredMidPeriod,
      boolean transferredUnderTwoMonths,
      long daysAtNewUnit) {
  }

  public static void validateAssignmentFields(String kind, String branch, ValidPeriod validPeriod) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(validPeriod, "validPeriod");
    if (!ALLOWED_KINDS.contains(kind)) {
      throw new OrgRuleViolation(
          "ORG-422-INVALID-ASSIGNMENT-KIND",
          "BR-01-08",
          null,
          "kind",
          "Loại phân công không hợp lệ: " + kind);
    }
    if (!ALLOWED_BRANCHES.contains(branch)) {
      throw new OrgRuleViolation(
          "ORG-422-INVALID-BRANCH",
          "BR-01-08",
          null,
          "branch",
          "Nhánh phân công không hợp lệ: " + branch);
    }
  }

  /**
   * Kiểm tra {@code BR-01-08} và lập kế hoạch ghi nhận phân công mới trên dòng thời gian hiện hành
   * ({@code recorded_to IS NULL}) của một cán bộ.
   */
  public static AssignmentPlan planNewAssignment(
      List<AssignmentVersion> currentBranchAssignments,
      AssignmentVersion candidate,
      boolean autoClosePreviousPrimary) {
    validateAssignmentFields(candidate.kind(), candidate.branch(), candidate.validPeriod());

    boolean isPrimaryOrTransfer =
        "CHINH".equals(candidate.kind()) || "DIEU_DONG".equals(candidate.kind());
    boolean shouldAutoClose = autoClosePreviousPrimary || "DIEU_DONG".equals(candidate.kind());

    List<UUID> toClose = new ArrayList<>();
    List<AssignmentVersion> toInsert = new ArrayList<>();
    List<UUID> changedAssignmentIds = new ArrayList<>();

    if (isPrimaryOrTransfer) {
      Map<UUID, List<AssignmentVersion>> primaryByLogicalId = new LinkedHashMap<>();
      for (AssignmentVersion existing : currentBranchAssignments) {
        if (existing.recordedTo() != null) {
          continue;
        }
        if (!existing.branch().equals(candidate.branch())) {
          continue;
        }
        if ("CHINH".equals(existing.kind())) {
          primaryByLogicalId.computeIfAbsent(existing.id(), k -> new ArrayList<>()).add(existing);
        }
      }

      LocalDate newFrom = candidate.validPeriod().from();
      LocalDate newTo = candidate.validPeriod().to();
      for (Map.Entry<UUID, List<AssignmentVersion>> entry : primaryByLogicalId.entrySet()) {
        List<AssignmentVersion> slices = entry.getValue();
        boolean overlaps = slices.stream().anyMatch(s -> s.validPeriod().overlaps(newFrom, newTo));
        if (!overlaps) {
          continue;
        }

        boolean canCloseAtNewFrom =
            shouldAutoClose
                && slices.stream().allMatch(s -> s.validPeriod().from().isBefore(newFrom));
        if (!canCloseAtNewFrom) {
          throw new OrgRuleViolation(
              "ORG-422-PRIMARY-OVERLAP",
              "BR-01-08",
              null,
              "validFrom",
              "Mỗi cán bộ chỉ có một phân công chính trên nhánh "
                  + candidate.branch()
                  + " tại một thời điểm.");
        }

        VersionPlan<AssignmentVersion> endPlan = Timeline.endAt(slices, newFrom);
        toClose.addAll(endPlan.toClose());
        toInsert.addAll(endPlan.toInsert());
        changedAssignmentIds.add(entry.getKey());
      }
    }

    toInsert.add(candidate);
    changedAssignmentIds.add(candidate.id());
    return new AssignmentPlan(
        new VersionPlan<>(List.copyOf(toClose), List.copyOf(toInsert)),
        List.copyOf(changedAssignmentIds));
  }

  /**
   * Kiểm tra và lập kế hoạch ghi nhận quan hệ báo cáo mới ({@code org.reporting_line}),
   * tự động đóng quan hệ báo cáo hiện hành trùng {@code (subordinate, lineType, branch)} nếu bắt đầu trước
   * hoặc từ chối bằng {@link OrgRuleViolation} nếu chồng lấp không hợp lệ.
   */
  public static VersionPlan<ReportingLineVersion> planReportingLine(
      List<ReportingLineVersion> currentSameKeyLines,
      ReportingLineVersion candidate,
      boolean autoClosePrevious) {
    List<UUID> toClose = new ArrayList<>();
    List<ReportingLineVersion> toInsert = new ArrayList<>();
    LocalDate newFrom = candidate.validPeriod().from();
    LocalDate newTo = candidate.validPeriod().to();

    Map<UUID, List<ReportingLineVersion>> byLogicalId = new LinkedHashMap<>();
    for (ReportingLineVersion existing : currentSameKeyLines) {
      if (existing.recordedTo() == null
          && existing.subordinatePersonId().equals(candidate.subordinatePersonId())
          && existing.lineType().equals(candidate.lineType())
          && existing.branch().equals(candidate.branch())) {
        byLogicalId.computeIfAbsent(existing.id(), k -> new ArrayList<>()).add(existing);
      }
    }

    for (List<ReportingLineVersion> slices : byLogicalId.values()) {
      boolean overlaps = slices.stream().anyMatch(s -> s.validPeriod().overlaps(newFrom, newTo));
      if (!overlaps) {
        continue;
      }
      boolean canClose =
          autoClosePrevious
              && slices.stream().allMatch(s -> s.validPeriod().from().isBefore(newFrom));
      if (!canClose) {
        throw new OrgRuleViolation(
            "ORG-422-REPORTING-LINE-OVERLAP",
            "BR-01-08",
            null,
            "validFrom",
            "Quan hệ báo cáo "
                + candidate.lineType()
                + " trên nhánh "
                + candidate.branch()
                + " bị chồng lấp thời gian hiệu lực.");
      }
      VersionPlan<ReportingLineVersion> endPlan = Timeline.endAt(slices, newFrom);
      toClose.addAll(endPlan.toClose());
      toInsert.addAll(endPlan.toInsert());
    }

    toInsert.add(candidate);
    return new VersionPlan<>(List.copyOf(toClose), List.copyOf(toInsert));
  }

  /**
   * {@code BR-01-09}: Xác định đơn vị đánh giá cho một cán bộ trong kỳ đánh giá {@code [periodStart, periodEnd]}
   * (tính theo ngày bao gồm cả {@code periodStart} và {@code periodEnd}).
   */
  public static EvaluationUnitResolution danhGiaTaiDonVi(
      List<AssignmentVersion> branchAssignments, LocalDate periodStart, LocalDate periodEnd) {
    Objects.requireNonNull(branchAssignments, "branchAssignments");
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(periodEnd, "periodEnd");
    if (periodEnd.isBefore(periodStart)) {
      throw new OrgRuleViolation(
          "ORG-422-INVALID-PERIOD",
          "BR-01-09",
          null,
          "periodEnd",
          "Ngày kết thúc kỳ đánh giá không được trước ngày bắt đầu.");
    }

    LocalDate periodEndExclusive = periodEnd.plusDays(1);

    List<AssignmentVersion> relevant =
        branchAssignments.stream()
            .filter(a -> a.recordedTo() == null)
            .filter(a -> "CHINH".equals(a.kind()) || "DIEU_DONG".equals(a.kind()))
            .filter(a -> a.validPeriod().overlaps(periodStart, periodEndExclusive))
            .sorted(
                Comparator.comparing((AssignmentVersion a) -> a.validPeriod().from())
                    .thenComparingInt(AssignmentVersion::rowVersion))
            .toList();

    if (relevant.isEmpty()) {
      throw new OrgNotFoundException(
          "Không tìm thấy phân công chính trong kỳ đánh giá để xác định đơn vị đánh giá.");
    }

    AssignmentVersion latest = relevant.get(relevant.size() - 1);
    LocalDate latestStartInPeriod =
        latest.validPeriod().from().isBefore(periodStart)
            ? periodStart
            : latest.validPeriod().from();
    LocalDate latestEndExclusiveInPeriod =
        latest.validPeriod().to() == null || latest.validPeriod().to().isAfter(periodEndExclusive)
            ? periodEndExclusive
            : latest.validPeriod().to();
    long daysAtNewUnit = ChronoUnit.DAYS.between(latestStartInPeriod, latestEndExclusiveInPeriod);

    AssignmentVersion previous = null;
    for (int i = relevant.size() - 2; i >= 0; i--) {
      if (!relevant.get(i).orgUnitId().equals(latest.orgUnitId())) {
        previous = relevant.get(i);
        break;
      }
    }

    if (previous == null || !latest.validPeriod().from().isAfter(periodStart)) {
      return new EvaluationUnitResolution(
          latest.orgUnitId(), latest.orgUnitId(), null, false, false, daysAtNewUnit);
    }

    boolean atLeastTwoMonths =
        !latestStartInPeriod.plusMonths(2).isAfter(latestEndExclusiveInPeriod);
    boolean underTwoMonths = !atLeastTwoMonths;
    UUID evaluatingUnitId = underTwoMonths ? previous.orgUnitId() : latest.orgUnitId();

    return new EvaluationUnitResolution(
        evaluatingUnitId,
        latest.orgUnitId(),
        previous.orgUnitId(),
        true,
        underTwoMonths,
        daysAtNewUnit);
  }
}
