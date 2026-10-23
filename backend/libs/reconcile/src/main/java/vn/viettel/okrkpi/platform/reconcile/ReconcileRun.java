package vn.viettel.okrkpi.platform.reconcile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một dòng {@code <schema>.reconcile_run}: cột theo {@code org_replica.reconcile_run} của TL-03,
 * thêm {@code job} (mã job R1–R7) và {@code detail} (JSON: phạm vi, giá trị mong đợi, bucket lệch,
 * kế hoạch phát lại). Không ghi dữ liệu C2, C3 vào {@code detail}.
 */
public record ReconcileRun(
    UUID id,
    UUID tenantId,
    String job,
    String entityType,
    Instant startedAt,
    Instant finishedAt,
    Integer bucketsTotal,
    Integer bucketsMismatch,
    Integer rowsRepaired,
    RunStatus status,
    String detail) {

  public ReconcileRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(status, "status");
    detail = detail == null || detail.isBlank() ? "{}" : detail;
  }

  /** Lần chạy mới ở trạng thái {@link RunStatus#DANG_CHAY}. */
  public static ReconcileRun start(
      UUID id,
      UUID tenantId,
      String job,
      String entityType,
      Instant at,
      int buckets,
      String detail) {
    return new ReconcileRun(
        id, tenantId, job, entityType, at, null, buckets, null, null, RunStatus.DANG_CHAY, detail);
  }
}
