package vn.viettel.okrkpi.org.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.application.port.ReportingLineStore;
import vn.viettel.okrkpi.org.domain.AssignmentRules;
import vn.viettel.okrkpi.org.domain.AssignmentRules.AssignmentPlan;
import vn.viettel.okrkpi.org.domain.AssignmentRules.EvaluationUnitResolution;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.ReportingLineVersion;
import vn.viettel.okrkpi.org.domain.Timeline;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.ValidPeriod;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/**
 * Dịch vụ miền ghi nhận phân công cán bộ ({@code org.assignment}) và đường báo cáo ({@code org.reporting_line})
 * theo mô hình hai trục thời gian (QT-01, BR-01-08, BR-01-09, EVT-15).
 *
 * <p>Hợp đồng {@code org.yaml} hiện không khai báo endpoint {@code POST /api/v1/org/assignments} trực tiếp;
 * dịch vụ này là đầu mối nghiệp vụ để các luồng thay đổi tổ chức ({@code changeOrgUnit}), đối soát nhân sự
 * ({@code resolveOrgDiscrepancies}), SCIM ({@code DV-04-06}) và kiểm thử tích hợp gọi thống nhất.
 */
@Service
public class AssignmentCommandService {

  private final AssignmentStore assignments;
  private final ReportingLineStore reportingLines;
  private final ChangeOrderStore orders;
  private final UnitSupport support;
  private final Clock clock;

  public AssignmentCommandService(
      AssignmentStore assignments,
      ReportingLineStore reportingLines,
      ChangeOrderStore orders,
      UnitSupport support,
      Clock clock) {
    this.assignments = assignments;
    this.reportingLines = reportingLines;
    this.orders = orders;
    this.support = support;
    this.clock = clock;
  }

  /**
   * Lệnh ghi nhận phân công mới (bổ nhiệm chính, kiêm nhiệm, điều động, biệt phái, tạm giữ) và quan hệ báo cáo kèm theo.
   */
  public record AssignCommand(
      UUID personId,
      UUID orgUnitId,
      UUID positionId,
      UUID jobTitleId,
      String kind,
      String branch,
      boolean head,
      LocalDate validFrom,
      LocalDate validTo,
      boolean autoClosePreviousPrimary,
      String decisionNo,
      LocalDate decisionDate,
      String signedDocRef,
      UUID superiorPersonId,
      String reportingLineType) {
  }

  /**
   * Lệnh điều động sang vị trí chính mới từ ngày hiệu lực (tự động đóng phân công chính hiện hành cùng nhánh ở ngày liền trước).
   */
  public record TransferCommand(
      UUID personId,
      UUID toOrgUnitId,
      UUID toPositionId,
      UUID toJobTitleId,
      String branch,
      boolean head,
      LocalDate effectiveDate,
      LocalDate validTo,
      String decisionNo,
      LocalDate decisionDate,
      String signedDocRef,
      UUID superiorPersonId,
      String reportingLineType) {
  }

  /**
   * Lệnh ghi nhận quan hệ báo cáo riêng ({@code TRUC_TIEP}, {@code THAM_DINH}, {@code PHU_TRACH_LINH_VUC}).
   */
  public record ReportingLineCommand(
      UUID subordinatePersonId,
      UUID superiorPersonId,
      String lineType,
      String branch,
      LocalDate validFrom,
      LocalDate validTo,
      boolean autoClosePrevious,
      UUID changeOrderId) {
  }

  /**
   * Ghi nhận phân công mới cho cán bộ:
   * <ul>
   *   <li>Kiểm tra hợp lệ loại phân công, nhánh và khoảng hiệu lực.</li>
   *   <li>Nếu {@code autoClosePreviousPrimary = true} và là phân công {@code CHINH}, tự động đóng các lát cắt
   *       {@code CHINH} hiện có trên cùng nhánh tại {@code validFrom} (bổ nhiệm/điều động tương lai, BR-01-08).</li>
   *   <li>Kiểm tra không còn hai phân công {@code CHINH} chồng lấn trên cùng nhánh trước khi xuống CSDL.</li>
   *   <li>Nếu có kèm {@code superiorPersonId}, ghi nhận dòng {@code org.reporting_line} trong cùng giao dịch.</li>
   *   <li>Phát sự kiện {@code EVT-15} ({@code org.assignment.changed.v1}) qua outbox cho mọi phân công bị ảnh hưởng.</li>
   * </ul>
   */
  @Transactional
  public UUID recordAssignment(Actor actor, AssignCommand cmd) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(cmd, "cmd");
    ValidPeriod period = new ValidPeriod(cmd.validFrom(), cmd.validTo());
    AssignmentRules.validateAssignmentFields(cmd.kind(), cmd.branch(), period);

    UUID changeOrderId = createChangeOrderIfPresent(actor, cmd);

    List<AssignmentVersion> current = assignments.currentForPersonForUpdate(cmd.personId());
    UUID assignmentId = UuidV7.next(clock);
    AssignmentVersion candidate = new AssignmentVersion(
        UuidV7.next(clock),
        assignmentId,
        cmd.personId(),
        cmd.orgUnitId(),
        cmd.positionId(),
        cmd.jobTitleId(),
        cmd.kind(),
        cmd.branch(),
        cmd.head(),
        changeOrderId,
        period,
        null,
        null,
        0);

    AssignmentPlan result =
        AssignmentRules.planNewAssignment(current, candidate, cmd.autoClosePreviousPrimary());

    assignments.apply(result.versionPlan(), changeOrderId, actor);

    for (UUID changedId : result.changedAssignmentIds()) {
      support.emitAssignmentChanged(changedId, changeOrderId, actor);
    }

    if (cmd.superiorPersonId() != null) {
      String lineType = (cmd.reportingLineType() == null || cmd.reportingLineType().isBlank())
          ? "TRUC_TIEP"
          : cmd.reportingLineType();
      recordReportingLine(
          actor,
          new ReportingLineCommand(
              cmd.personId(),
              cmd.superiorPersonId(),
              lineType,
              cmd.branch(),
              cmd.validFrom(),
              cmd.validTo(),
              true,
              changeOrderId));
    }

    return assignmentId;
  }

  /**
   * Điều động cán bộ sang đơn vị mới giữ vị trí chính từ {@code effectiveDate}, tự động kết thúc phân công chính hiện
   * tại trên cùng nhánh ở ngày trước đó (hiểu theo nửa mở {@code [valid_from, effectiveDate)}) trong cùng giao dịch.
   */
  @Transactional
  public UUID transferPrimaryAssignment(Actor actor, TransferCommand cmd) {
    Objects.requireNonNull(cmd, "cmd");
    return recordAssignment(
        actor,
        new AssignCommand(
            cmd.personId(),
            cmd.toOrgUnitId(),
            cmd.toPositionId(),
            cmd.toJobTitleId(),
            "CHINH",
            cmd.branch(),
            cmd.head(),
            cmd.effectiveDate(),
            cmd.validTo(),
            true,
            cmd.decisionNo(),
            cmd.decisionDate(),
            cmd.signedDocRef(),
            cmd.superiorPersonId(),
            cmd.reportingLineType()));
  }

  /**
   * Đóng (kết thúc) một phân công hiện có từ ngày {@code effectiveEndExclusive} (ngày làm việc cuối là ngày liền trước).
   */
  @Transactional
  public void endAssignment(Actor actor, UUID assignmentId, LocalDate effectiveEndExclusive, UUID changeOrderId) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(assignmentId, "assignmentId");
    Objects.requireNonNull(effectiveEndExclusive, "effectiveEndExclusive");
    List<AssignmentVersion> slices = assignments.currentVersions(assignmentId);
    if (slices.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy phân công: " + assignmentId);
    }
    VersionPlan<AssignmentVersion> plan = Timeline.endAt(slices, effectiveEndExclusive);
    if (!plan.isEmpty()) {
      assignments.apply(plan, changeOrderId, actor);
      support.emitAssignmentChanged(assignmentId, changeOrderId, actor);
    }
  }

  /**
   * Ghi nhận hoặc cập nhật đường báo cáo ({@code org.reporting_line}).
   */
  @Transactional
  public UUID recordReportingLine(Actor actor, ReportingLineCommand cmd) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(cmd, "cmd");
    ValidPeriod period = new ValidPeriod(cmd.validFrom(), cmd.validTo());
    UUID lineId = UuidV7.next(clock);
    ReportingLineVersion draft = ReportingLineVersion.draft(
        lineId,
        cmd.subordinatePersonId(),
        cmd.superiorPersonId(),
        cmd.lineType(),
        cmd.branch(),
        cmd.changeOrderId(),
        period);

    List<ReportingLineVersion> existing = reportingLines.currentForSubordinateForUpdate(cmd.subordinatePersonId());
    VersionPlan<ReportingLineVersion> plan =
        AssignmentRules.planReportingLine(existing, draft, cmd.autoClosePrevious());
    reportingLines.apply(plan, cmd.changeOrderId(), actor);
    return lineId;
  }

  /**
   * Đóng một đường báo cáo từ ngày {@code effectiveEndExclusive}.
   */
  @Transactional
  public void endReportingLine(Actor actor, UUID reportingLineId, LocalDate effectiveEndExclusive, UUID changeOrderId) {
    Objects.requireNonNull(actor, "actor");
    List<ReportingLineVersion> slices = reportingLines.currentVersionsForUpdate(reportingLineId);
    if (slices.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy đường báo cáo: " + reportingLineId);
    }
    VersionPlan<ReportingLineVersion> plan = Timeline.endAt(slices, effectiveEndExclusive);
    if (!plan.isEmpty()) {
      reportingLines.apply(plan, changeOrderId, actor);
    }
  }

  /**
   * BR-01-09: Xác định đơn vị đánh giá ({@code danh_gia_tai_don_vi}) cho cán bộ trong kỳ {@code [periodStart, periodEnd]}.
   * Nếu cán bộ được điều động giữa kỳ và thời gian công tác tại đơn vị mới trong kỳ chưa đủ 2 tháng thì đơn vị cũ
   * thực hiện đánh giá; từ đủ 2 tháng trở lên thì đơn vị mới thực hiện đánh giá.
   */
  @Transactional(readOnly = true)
  public EvaluationUnitResolution danhGiaTaiDonVi(
      UUID personId, String branch, LocalDate periodStart, LocalDate periodEnd) {
    List<AssignmentVersion> slices = assignments.findCurrentByPersonAndBranch(personId, branch);
    return AssignmentRules.danhGiaTaiDonVi(slices, periodStart, periodEnd);
  }

  private UUID createChangeOrderIfPresent(Actor actor, AssignCommand cmd) {
    if (cmd.decisionNo() == null || cmd.decisionNo().isBlank()) {
      return null;
    }
    String orderKind = switch (cmd.kind()) {
      case "DIEU_DONG" -> "DIEU_DONG";
      case "KIEM_NHIEM" -> "KIEM_NHIEM";
      default -> cmd.autoClosePreviousPrimary() ? "DIEU_DONG" : "BO_NHIEM";
    };
    LocalDate orderDate = cmd.decisionDate() != null ? cmd.decisionDate() : cmd.validFrom();
    ChangeOrderRecord order = new ChangeOrderRecord(
        UuidV7.next(clock),
        cmd.decisionNo().trim(),
        orderDate,
        orderKind,
        cmd.validFrom(),
        cmd.signedDocRef(),
        Map.of(
            "personId", cmd.personId().toString(),
            "orgUnitId", cmd.orgUnitId().toString(),
            "assignmentKind", cmd.kind(),
            "branch", cmd.branch()),
        "DA_AP_DUNG",
        null);
    return orders.insert(order, actor).id();
  }
}
