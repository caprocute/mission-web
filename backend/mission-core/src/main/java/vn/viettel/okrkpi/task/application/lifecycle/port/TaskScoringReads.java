package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi.ResponsibleItem;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;

/**
 * Đọc chỉ đọc phục vụ đầu vào tính điểm của MOD-04 (CORE-04-13, BS-03 DC-01, DC-03) trên {@code
 * taskDataSource}.
 */
public interface TaskScoringReads {

  /** Việc lá người đó chủ trì, sắp theo id, sau {@code afterId}. */
  List<WorkItemSnapshot> workItems(
      TenantId tenant, UUID periodId, UUID personId, UUID afterId, int limit);

  /** Trang đầu việc lá của nhiều người, mỗi người tối đa {@code limitPerPerson}. */
  Map<UUID, List<WorkItemSnapshot>> workItems(
      TenantId tenant, UUID periodId, Collection<UUID> personIds, int limitPerPerson);

  /** Mục có việc con người đó chủ trì, kèm tổng của các việc lá gần nhất. */
  List<ResponsibleItem> responsibleItems(TenantId tenant, UUID periodId, UUID personId);
}
