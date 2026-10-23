package vn.viettel.okrkpi.workflow.application.dual;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.port.BindingReader;
import vn.viettel.okrkpi.workflow.application.stage.OverdueCounts;
import vn.viettel.okrkpi.workflow.application.stage.StageScheduler;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval.Branch;
import vn.viettel.okrkpi.workflow.domain.stage.StagePlan;

/**
 * Phần tính toán của listener {@code ${lapLichKep}} (tính toán thuần, đọc bản sao cục bộ, TL-01 G2
 * quy tắc 4): lúc khởi tạo thì chụp kế hoạch hai nhánh từ {@code wf_binding}; khi vào một nhánh thì
 * đặt nhóm ứng viên, người được giao, hạn và mốc nhắc của nhánh đó; khi đi qua nhánh quá hạn thì
 * tăng số lần quá hạn để lần vào sau giao người được uỷ quyền hoặc lên cấp trên.
 *
 * <p>Biến mỗi nhánh có tên riêng ({@code nhom_chiHuy}, {@code han_capUy}…) vì hai nhánh song song
 * cùng ghi vào phạm vi tiến trình.
 */
@Component
public class DualApprovalHooks {

  private final BindingReader bindings;
  private final DualApprovalPlanner planner;
  private final StageScheduler scheduler;

  public DualApprovalHooks(
      BindingReader bindings, DualApprovalPlanner planner, StageScheduler scheduler) {
    this.bindings = bindings;
    this.planner = planner;
    this.scheduler = scheduler;
  }

  /** Biến ghi lúc khởi tạo: kế hoạch, cờ ký của từng nhánh. */
  public Map<String, Object> start(Map<String, Object> variables) {
    UUID tenant = tenant(variables);
    ApprovalFlow flow = flow(variables);
    DualApprovalPlanner.Planned planned =
        planner.plan(bindings.rawParams(tenant, DualApproval.PROCESS_KIND, flow));
    Map<String, Object> out = new HashMap<>();
    out.put(DualApproval.PLAN, planned.plan().encode());
    out.put(DualApproval.OVERDUE_COUNTS, "");
    for (Branch b : Branch.values()) {
      out.put(b.signRequiredVariable(), planned.signs().getOrDefault(b, false));
      out.put(b.signedVariable(), false);
    }
    return out;
  }

  /** Biến ghi khi vào nhánh {@code elementId}. */
  public Map<String, Object> enterBranch(String elementId, Map<String, Object> variables) {
    Branch branch =
        Branch.byElement(elementId)
            .filter(b -> b.elementId().equals(elementId))
            .orElseThrow(() -> new ApprovalSetupException("Phần tử không phải nhánh duyệt kép"));
    StagePlan.Planned planned =
        StagePlan.decode(String.valueOf(variables.get(DualApproval.PLAN)))
            .step(elementId)
            .orElseThrow(() -> new ApprovalSetupException("Kế hoạch thiếu nhánh duyệt"));
    UUID unit =
        uuid(variables.get(ApprovalVariables.DON_VI_ID))
            .orElseThrow(() -> new ApprovalSetupException("Thiếu đơn vị"));
    StageScheduler.Assignment a =
        scheduler.forRole(
            tenant(variables),
            flow(variables),
            unit,
            planned,
            OverdueCounts.of(variables.get(DualApproval.OVERDUE_COUNTS), elementId));
    Map<String, Object> out = new HashMap<>();
    out.put(branch.groupVariable(), a.candidateGroup());
    out.put(branch.assigneeVariable(), a.assigneeId() == null ? null : a.assigneeId().toString());
    out.put(branch.dueVariable(), a.due().toString());
    out.put(branch.remindVariable(), a.remind().toString());
    return out;
  }

  /** Giá trị mới của số lần quá hạn khi đi qua nhánh ra khỏi sự kiện {@code boundaryId}. */
  public String overdue(String boundaryId, Object counts) {
    if (!boundaryId.startsWith(DualApproval.OVERDUE_PREFIX)
        || Branch.byElement(boundaryId).isEmpty()) {
      throw new ApprovalSetupException("Nhánh quá hạn phải xuất phát từ sự kiện quaHan_<nhánh>");
    }
    return OverdueCounts.increment(
        counts, boundaryId.substring(DualApproval.OVERDUE_PREFIX.length()));
  }

  private static UUID tenant(Map<String, Object> variables) {
    return uuid(variables.get(ApprovalVariables.TENANT_ID))
        .orElseThrow(() -> new ApprovalSetupException("Thiếu tenant của tiến trình"));
  }

  private static ApprovalFlow flow(Map<String, Object> variables) {
    return ApprovalFlow.parse(String.valueOf(variables.get(ApprovalVariables.KHOI)))
        .orElseThrow(() -> new ApprovalSetupException("Thiếu luồng của tiến trình"));
  }

  private static Optional<UUID> uuid(Object value) {
    if (value == null || value.toString().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value.toString()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
