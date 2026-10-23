package vn.viettel.okrkpi.workflow.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Số việc đang mở theo người được giao (chọn người ít việc nhất, QT-12 bước 3). */
public interface AssigneeWorkload {

  /** Số user task đang mở của từng người trong tenant. */
  Map<UUID, Long> openTasks(UUID tenantId, Collection<UUID> people);
}
