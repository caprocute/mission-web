package vn.viettel.okrkpi.shared.job;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gộp các {@link JobSource} cho {@code GET /api/v1/jobs/{id}} ({@code getJob}) và {@code DELETE}
 * ({@code cancelJob}) của {@code jobs.yaml}: chỉ người khởi tạo xem và huỷ được, người khác nhận
 * 404 như khi tác vụ không tồn tại (không lộ sự tồn tại).
 */
public class JobQueryService {

  /** Mã hợp đồng khi tác vụ đã kết thúc hoặc không cho huỷ. */
  public static final String NOT_CANCELLABLE = "JOB-409-NOT-CANCELLABLE";

  private final List<JobSource> sources;

  public JobQueryService(List<JobSource> sources) {
    this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
  }

  /** Trạng thái tác vụ nếu người gọi là người khởi tạo. */
  public Optional<Job> find(TenantId tenant, ActorId caller, String jobId) {
    return locate(tenant, caller, jobId).map(f -> f.tracked().job());
  }

  /**
   * Trạng thái tác vụ cho {@code getJob}.
   *
   * @throws JobNotFoundException không có hoặc người gọi không phải người khởi tạo (404)
   */
  public Job get(TenantId tenant, ActorId caller, String jobId) {
    return find(tenant, caller, jobId).orElseThrow(JobNotFoundException::new);
  }

  /**
   * Yêu cầu huỷ cho {@code cancelJob}; thành công thì service trả 202.
   *
   * @throws JobNotFoundException không có hoặc người gọi không phải người khởi tạo (404)
   * @throws BusinessException {@value #NOT_CANCELLABLE} khi đã kết thúc hoặc không cho huỷ (409)
   */
  public Job cancel(TenantId tenant, ActorId caller, String jobId) {
    Found found = locate(tenant, caller, jobId).orElseThrow(JobNotFoundException::new);
    TrackedJob tracked = found.tracked();
    if (!tracked.cancellable()
        || tracked.job().status().isTerminal()
        || !found.source().requestCancel(tenant, jobId)) {
      throw new BusinessException(NOT_CANCELLABLE);
    }
    return tracked.job();
  }

  private Optional<Found> locate(TenantId tenant, ActorId caller, String jobId) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(caller, "caller");
    if (jobId == null || jobId.isBlank()) {
      return Optional.empty();
    }
    for (JobSource source : sources) {
      Optional<TrackedJob> tracked = source.find(tenant, jobId);
      if (tracked.isPresent()) {
        return tracked.filter(t -> t.initiator().equals(caller)).map(t -> new Found(source, t));
      }
    }
    return Optional.empty();
  }

  private record Found(JobSource source, TrackedJob tracked) {}
}
