package vn.viettel.okrkpi.workflow.application.stage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.application.port.WorkingCalendar;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.domain.stage.OverdueAction;
import vn.viettel.okrkpi.workflow.domain.stage.StageDeadlines;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;
import vn.viettel.okrkpi.workflow.domain.stage.StageStep;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * Tính người làm, nhóm ứng viên, hạn và mốc nhắc mỗi lần tiến trình vào một bước (listener {@code
 * ${lapLichBuoc}}). Chỉ đọc bản sao cục bộ (cây tổ chức, lịch nghỉ), không gọi hệ ngoài (TL-01 G2
 * quy tắc 4).
 *
 * <p>Quá hạn lần thứ {@code n}: {@code CHUYEN_CAP_TREN} giao cùng vai trò ở đơn vị cấp trên thứ
 * {@code n}; {@code CHUYEN_CAP_PHO_UY_QUYEN} giao cho người thay của {@code wf_binding}, không khai
 * người thay thì lên cấp trên; {@code NHAC_LAI} giữ người làm và cho thêm một khoảng hạn.
 */
@Component
public class StageScheduler {

  /** Số bước đi lên tối đa khi tìm đơn vị (như DV-06-03). */
  static final int MAX_HOPS = 12;

  private final OrgDirectory directory;
  private final WorkingCalendar calendar;
  private final Clock clock;

  public StageScheduler(OrgDirectory directory, WorkingCalendar calendar, Clock clock) {
    this.directory = directory;
    this.calendar = calendar;
    this.clock = clock;
  }

  /**
   * Dữ kiện của lần vào bước.
   *
   * @param tenantId tenant
   * @param template mẫu
   * @param elementId user task
   * @param plan kế hoạch bước chụp lúc khởi tạo
   * @param flow luồng
   * @param subjectUnitId đơn vị của đối tượng
   * @param chosenUnitId đơn vị nơi worker đã chọn người, có thể {@code null}
   * @param subjectPersonId người được đánh giá, có thể {@code null}
   * @param chosenPersonId người worker đã chọn cho bước, có thể {@code null}
   * @param overdueCount số lần bước đã quá hạn
   * @param overallDue hạn tổng thể (khiếu nại), có thể {@code null}
   */
  public record Inputs(
      UUID tenantId,
      StageTemplate template,
      String elementId,
      StagePlan plan,
      ApprovalFlow flow,
      UUID subjectUnitId,
      UUID chosenUnitId,
      UUID subjectPersonId,
      UUID chosenPersonId,
      int overdueCount,
      Instant overallDue) {

    public Inputs {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(template, "template");
      Objects.requireNonNull(elementId, "elementId");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(flow, "flow");
      Objects.requireNonNull(subjectUnitId, "subjectUnitId");
    }
  }

  /**
   * Kết quả.
   *
   * @param candidateGroup nhóm ứng viên {@code <tenant>:<role>:<unit>}
   * @param assigneeId người được giao, có thể {@code null} (việc của nhóm)
   * @param due hạn
   * @param remind mốc nhắc
   */
  public record Assignment(String candidateGroup, UUID assigneeId, Instant due, Instant remind) {}

  /** Người làm, nhóm, hạn của lần vào bước. */
  public Assignment schedule(Inputs in) {
    StageStep step =
        in.template()
            .step(in.elementId())
            .orElseThrow(() -> new ApprovalSetupException("Bước không thuộc mẫu"));
    StagePlan.Planned planned =
        in.plan()
            .step(in.elementId())
            .orElseThrow(() -> new ApprovalSetupException("Kế hoạch thiếu bước " + step.code()));
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, StageDeadlines.ZONE);
    UUID assignee = null;
    UUID unit;
    switch (step.target()) {
      case NGUOI -> {
        assignee = required(in.subjectPersonId(), "người được đánh giá");
        unit = in.subjectUnitId();
      }
      case CHON -> {
        assignee = in.chosenPersonId();
        unit = in.chosenUnitId() == null ? in.subjectUnitId() : in.chosenUnitId();
      }
      default -> {
        UUID base =
            in.chosenUnitId() != null && in.template() == StageTemplate.XU_LY_KHIEU_NAI
                ? in.chosenUnitId()
                : base(in.tenantId(), in.subjectUnitId(), in.flow(), planned, today);
        unit = base;
        if (in.overdueCount() > 0) {
          OverdueAction action = planned.overdue();
          if (action == OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN && planned.substituteId() != null) {
            assignee = planned.substituteId();
          } else if (action != OverdueAction.NHAC_LAI) {
            unit = ancestor(in.tenantId(), base, in.flow(), in.overdueCount(), today);
          }
        }
      }
    }
    Map<LocalDate, Boolean> overrides =
        calendar.overrides(in.tenantId(), today.minusDays(31), today.plusDays(90));
    LocalDate dueDay;
    if (step.fromStart() && in.overallDue() != null && in.overallDue().isAfter(now)) {
      dueDay = LocalDate.ofInstant(in.overallDue(), StageDeadlines.ZONE);
    } else if (step.fromStart() && in.overallDue() != null) {
      // Đã quá hạn tổng thể: mỗi lần chuyển cấp cho thêm một ngày làm việc.
      dueDay = StageDeadlines.dueDay(today, 1, overrides);
    } else {
      dueDay = StageDeadlines.dueDay(today, planned.workingDays(), overrides);
    }
    StageDeadlines.Schedule s =
        StageDeadlines.schedule(now, dueDay, planned.remindBefore(), overrides);
    String group = new CandidateGroup(in.tenantId(), planned.role(), unit).groupId();
    return new Assignment(group, assignee, s.due(), s.remind());
  }

  /**
   * Người làm, nhóm, hạn của một bước giao theo vai trò ngoài bốn mẫu phần 2 (nhánh của mẫu duyệt
   * kép DV-06-12): cùng quy tắc phạm vi, quá hạn và lịch ngày làm việc với bước vai trò của {@link
   * #schedule}.
   */
  public Assignment forRole(
      UUID tenantId,
      ApprovalFlow flow,
      UUID subjectUnitId,
      StagePlan.Planned planned,
      int overdueCount) {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, StageDeadlines.ZONE);
    UUID unit = base(tenantId, subjectUnitId, flow, planned, today);
    UUID assignee = null;
    if (overdueCount > 0) {
      OverdueAction action = planned.overdue();
      if (action == OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN && planned.substituteId() != null) {
        assignee = planned.substituteId();
      } else if (action != OverdueAction.NHAC_LAI) {
        unit = ancestor(tenantId, unit, flow, overdueCount, today);
      }
    }
    Map<LocalDate, Boolean> overrides =
        calendar.overrides(tenantId, today.minusDays(31), today.plusDays(90));
    LocalDate dueDay = StageDeadlines.dueDay(today, planned.workingDays(), overrides);
    StageDeadlines.Schedule s =
        StageDeadlines.schedule(now, dueDay, planned.remindBefore(), overrides);
    String group = new CandidateGroup(tenantId, planned.role(), unit).groupId();
    return new Assignment(group, assignee, s.due(), s.remind());
  }

  private UUID base(
      UUID tenantId,
      UUID subjectUnitId,
      ApprovalFlow flow,
      StagePlan.Planned planned,
      LocalDate today) {
    OrgUnit unit = unit(tenantId, subjectUnitId, today);
    return switch (planned.scope()) {
      case DON_VI_TRUC_TIEP, QUAN_LY_TRUC_TIEP -> unit.id();
      case CAP_TREN_TRUC_TIEP -> {
        UUID parent = unit.parentOn(flow);
        yield parent == null ? unit.id() : parent;
      }
      case CAP_UY_CO_THAM_QUYEN -> committee(tenantId, unit, today);
    };
  }

  private UUID committee(UUID tenantId, OrgUnit start, LocalDate today) {
    UUID current = start.partyParentId();
    for (int hop = 0; hop < MAX_HOPS && current != null; hop++) {
      OrgUnit ancestor = unit(tenantId, current, today);
      if (ancestor.isPartyOrganization()) {
        return ancestor.id();
      }
      current = ancestor.partyParentId();
    }
    throw new ApprovalSetupException("Không tìm được cấp uỷ có thẩm quyền trên cây tổ chức");
  }

  /** Đơn vị cấp trên thứ {@code levels}; hết cây thì dừng ở đơn vị cao nhất. */
  UUID ancestor(UUID tenantId, UUID from, ApprovalFlow flow, int levels, LocalDate today) {
    UUID current = from;
    for (int i = 0; i < Math.min(levels, MAX_HOPS); i++) {
      UUID parent = unit(tenantId, current, today).parentOn(flow);
      if (parent == null) {
        break;
      }
      current = parent;
    }
    return current;
  }

  private OrgUnit unit(UUID tenantId, UUID unitId, LocalDate today) {
    return directory
        .unit(tenantId, unitId, today)
        .orElseThrow(
            () -> new ApprovalSetupException("Không tìm thấy đơn vị trong bản sao tổ chức"));
  }

  /** Biến lịch của bước để listener ghi (chuỗi ISO-8601 cho timer và {@code dueDate}). */
  public static Map<String, Object> variables(Assignment a) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put(StageVariables.NHOM_BUOC, a.candidateGroup());
    v.put(StageVariables.NGUOI_BUOC, a.assigneeId() == null ? null : a.assigneeId().toString());
    v.put(StageVariables.HAN_BUOC, a.due().toString());
    v.put(StageVariables.NHAC_BUOC, a.remind().toString());
    return v;
  }

  private static UUID required(UUID value, String what) {
    if (value == null) {
      throw new ApprovalSetupException("Thiếu " + what);
    }
    return value;
  }
}
