package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-05 {@code task.task.progressed.v1} qua outbox cùng giao dịch. */
public interface TaskProgressEvents {

  record Progressed(
      TenantId tenant,
      UUID taskId,
      UUID periodId,
      UUID progressId,
      UUID reporterId,
      BigDecimal percent,
      BigDecimal doneQuantity,
      Instant reportedAt,
      long aggregateVersion) {}

  void progressed(Progressed event);
}
