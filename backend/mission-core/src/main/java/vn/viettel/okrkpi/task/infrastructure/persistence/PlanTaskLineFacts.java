package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.plan.port.PlanLineFacts;

/**
 * {@link PlanLineFacts} từ nhiệm vụ gắn dòng ({@code task.task.plan_item_id}) và trạng thái minh
 * chứng MOD-03 cung cấp qua {@link TaskDeliverables} (CORE-02-10). Nhiệm vụ đã {@code HOAN_THANH}
 * được coi là có minh chứng xác nhận vì nghiệm thu đã xác nhận kết quả (suy luận).
 */
public class PlanTaskLineFacts implements PlanLineFacts {

  private final PlanTasks tasks;
  private final TaskDeliverables deliverables;

  public PlanTaskLineFacts(PlanTasks tasks, TaskDeliverables deliverables) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.deliverables = deliverables == null ? (t, id) -> java.util.List.of() : deliverables;
  }

  @Override
  public Map<UUID, LineFact> of(TenantId tenant, int periodBucket, Collection<UUID> planItemIds) {
    Map<UUID, LineFact> out = new LinkedHashMap<>();
    for (PlanTasks.LinkedTask t : tasks.linked(tenant, periodBucket, planItemIds)) {
      if (out.containsKey(t.planItemId())) {
        continue;
      }
      boolean confirmed =
          "HOAN_THANH".equals(t.status())
              || deliverables.of(tenant, t.id()).stream()
                  .anyMatch(d -> "DA_XAC_NHAN".equals(String.valueOf(d.get("status"))));
      out.put(t.planItemId(), new LineFact(t.planItemId(), t.status(), confirmed));
    }
    return out;
  }
}
