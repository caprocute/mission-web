package vn.viettel.okrkpi.workflow.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BatchJob;

/** Cổng ra tới {@code flowable.wf_task_batch}; mọi thao tác chạy trong giao dịch đang mở. */
public interface BatchJobStore {

  void create(BatchJob job);

  /** Ghi trạng thái, số đếm và kết quả của tác vụ. */
  void update(BatchJob job);

  Optional<BatchJob> find(UUID tenantId, UUID jobId);
}
