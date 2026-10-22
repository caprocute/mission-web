package vn.viettel.okrkpi.task.application.assign.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ nền {@code BULK_ASSIGN} của giao đồng loạt, theo dõi qua {@code GET /api/v1/jobs/{id}}.
 */
public interface BulkAssignJobs {

  /** Kết quả của một đơn vị nhận không giao được. */
  record Failure(UUID unitId, String message) {}

  void accepted(TenantId tenant, String jobId, UUID initiator, int total);

  void progress(TenantId tenant, String jobId, int done, int total);

  void finished(TenantId tenant, String jobId, int assigned, List<Failure> failures);
}
