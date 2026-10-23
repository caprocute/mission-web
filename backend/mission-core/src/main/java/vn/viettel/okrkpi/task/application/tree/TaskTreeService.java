package vn.viettel.okrkpi.task.application.tree;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.tree.port.TaskTreeRows;
import vn.viettel.okrkpi.task.application.tree.port.TaskTreeRows.NodeRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.tree.TaskTree;
import vn.viettel.okrkpi.task.domain.tree.TaskTree.AssignmentMode;

/**
 * Cây nhiệm vụ nhiều bậc và chế độ phân công theo hồ sơ (CORE-02-28, BS-03 DC-04, DC-06). Chế độ
 * ({@code che_do_phan_cong}) và khuôn phân rã ({@code khuon_phan_ra}) là tham số của Policy Pack áp
 * cho kỳ, đọc qua {@link PolicyQueryApi}; thiếu tham số thì giữ hành vi cũ.
 */
public class TaskTreeService {

  public static final String MODE_KEY = "che_do_phan_cong";
  public static final String TEMPLATE_KEY = "khuon_phan_ra";
  public static final String MODE_ERROR = "TASK-422-ASSIGNMENT-MODE";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String RULE = "DC-06";
  static final List<String> FLOWS = List.of("NHA_NUOC", "DANG");

  private final TaskTreeRows rows;
  private final PolicyQueryApi policies;

  public TaskTreeService(TaskTreeRows rows, PolicyQueryApi policies) {
    this.rows = Objects.requireNonNull(rows, "rows");
    this.policies = policies;
  }

  /** Chế độ phân công áp cho kỳ. */
  public AssignmentMode mode(TenantId tenant, UUID periodId) {
    return AssignmentMode.parse(parameter(tenant, periodId, MODE_KEY).orElse(null));
  }

  /** Khuôn phân rã áp cho kỳ: loại nút theo thứ tự từ gốc. */
  public List<String> template(TenantId tenant, UUID periodId) {
    return TaskTree.template(parameter(tenant, periodId, TEMPLATE_KEY).orElse(null));
  }

  /**
   * Kiểm vị trí của bản soạn {@code selfId} (rỗng khi chưa lưu) trong cây: việc cha cùng kỳ, chưa
   * huỷ, không tạo vòng, loại nút đúng bậc theo khuôn; vi phạm trả 422 nêu trường.
   */
  public void requirePlacement(
      TenantId tenant, UUID selfId, UUID periodId, TaskDraftSpec.Node node) {
    List<String> template = template(tenant, periodId);
    UUID parentId = node.parentTaskId();
    if (parentId == null) {
      String error = TaskTree.placement(template, List.of(), node.kind());
      if (error != null) {
        throw invalid("nodeKind", error);
      }
      return;
    }
    if (parentId.equals(selfId)) {
      throw invalid("parentTaskId", "Việc cha không được là chính công việc này.");
    }
    List<NodeRow> path = rows.path(tenant, parentId);
    if (path.isEmpty()) {
      throw invalid("parentTaskId", "Việc cha không tồn tại.");
    }
    NodeRow parent = path.get(path.size() - 1);
    if (!parent.periodId().equals(periodId)) {
      throw invalid("parentTaskId", "Việc cha phải thuộc cùng kỳ với công việc.");
    }
    if ("HUY".equals(parent.status())) {
      throw invalid("parentTaskId", "Việc cha đã bị huỷ.");
    }
    if (selfId != null && path.stream().anyMatch(n -> n.id().equals(selfId))) {
      throw invalid("parentTaskId", "Không chọn được việc cấp dưới của chính công việc này.");
    }
    String error =
        TaskTree.placement(template, path.stream().map(NodeRow::kind).toList(), node.kind());
    if (error != null) {
      throw invalid("nodeKind", error);
    }
  }

  /**
   * Vi phạm chế độ phân công khi giao nút {@code selfId}: {@code people} là số người thực hiện (chủ
   * trì và phối hợp là người), {@code unitLead} khi chủ trì là đơn vị.
   */
  public List<String> violations(
      TenantId tenant,
      UUID selfId,
      UUID periodId,
      TaskDraftSpec.Node node,
      int people,
      boolean unitLead) {
    AssignmentMode mode = mode(tenant, periodId);
    if (mode == AssignmentMode.CHU_TRI_PHOI_HOP) {
      return List.of();
    }
    boolean leaf =
        selfId == null
            || rows.children(tenant, selfId).stream().allMatch(c -> "HUY".equals(c.status()));
    BigDecimal parentScore = null;
    BigDecimal siblings = BigDecimal.ZERO;
    UUID parentId = node.parentTaskId();
    if (parentId != null) {
      parentScore = rows.node(tenant, parentId).map(NodeRow::score).orElse(null);
      for (NodeRow c : rows.children(tenant, parentId)) {
        if (!c.id().equals(selfId) && counted(c.status()) && c.componentScore() != null) {
          siblings = siblings.add(c.componentScore());
        }
      }
    }
    return TaskTree.violations(
        mode,
        leaf,
        people,
        unitLead,
        parentId != null,
        parentScore,
        siblings,
        node.componentScore());
  }

  /** Như {@link #violations}; có vi phạm thì 422 {@code TASK-422-ASSIGNMENT-MODE}. */
  public void requireMode(
      TenantId tenant,
      UUID selfId,
      UUID periodId,
      TaskDraftSpec.Node node,
      int people,
      boolean unitLead) {
    List<String> found = violations(tenant, selfId, periodId, node, people, unitLead);
    if (found.isEmpty()) {
      return;
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    for (String m : found) {
      errors.add(
          new ProblemFieldError(
              TaskTree.ONE_PERSON.equals(m) ? "coAssignees" : "componentScore", m, RULE));
    }
    throw new BusinessException(MODE_ERROR, found.get(0), errors);
  }

  /**
   * Tổng hợp việc cha {@code parentId} từ việc con (dùng cho {@code inputs} của Policy Pack): việc
   * cha chỉ tính là xong khi mọi việc con xong. Rỗng khi không có nút.
   */
  public Optional<TaskTree.Rollup> rollup(TenantId tenant, UUID parentId) {
    return rows.node(tenant, parentId)
        .map(
            p ->
                TaskTree.rollup(
                    mode(tenant, p.periodId()),
                    p.score(),
                    rows.children(tenant, parentId).stream()
                        .map(c -> new TaskTree.Child(c.status(), c.componentScore()))
                        .toList()));
  }

  private Optional<Object> parameter(TenantId tenant, UUID periodId, String key) {
    if (policies == null || periodId == null) {
      return Optional.empty();
    }
    for (String flow : FLOWS) {
      Optional<ResolvedPack> pack =
          policies.resolve(tenant, PeriodId.of(periodId), flow, null, null);
      if (pack.isPresent()) {
        return policies.parameter(tenant, pack.get().versionId(), key);
      }
    }
    return Optional.empty();
  }

  /** Việc con đã giao (khác {@code NHAP}) và chưa huỷ mới chiếm điểm thành phần của việc cha. */
  private static boolean counted(String status) {
    return !"NHAP".equals(status) && !"HUY".equals(status);
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }
}
