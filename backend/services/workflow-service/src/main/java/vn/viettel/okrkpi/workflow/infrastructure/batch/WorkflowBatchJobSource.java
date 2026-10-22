package vn.viettel.okrkpi.workflow.infrastructure.batch;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.workflow.application.BatchCompleteService;
import vn.viettel.okrkpi.workflow.domain.BatchJob;

/**
 * Nguồn tác vụ dài {@code wf_task_batch} cho {@code JobQueryService} của thư viện nền ({@code
 * jobs.yaml}): chỉ người khởi tạo xem được; lô không huỷ được.
 */
@Component
public class WorkflowBatchJobSource implements JobSource {

  private final BatchCompleteService batches;
  private final Clock clock;

  public WorkflowBatchJobSource(BatchCompleteService batches, Clock clock) {
    this.batches = batches;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "flowable.wf_task_batch";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    UUID id;
    try {
      id = UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return batches
        .find(tenant.value(), id)
        .map(
            job ->
                new TrackedJob(toJob(job, clock.instant()), ActorId.of(job.requestedBy()), false));
  }

  /** Lược đồ {@code Job} của hợp đồng cho một tác vụ lô. */
  public static Job toJob(BatchJob job, Instant now) {
    BatchJob.Status status = job.effectiveStatus(now);
    String message =
        switch (status) {
          case CHO -> "Đang chờ xử lý " + job.totalItems() + " việc";
          case DANG_CHAY ->
              "Đã xử lý "
                  + (job.doneItems() + job.failedItems())
                  + "/"
                  + job.totalItems()
                  + " việc";
          case HOAN_TAT ->
              "Đã duyệt "
                  + job.doneItems()
                  + "/"
                  + job.totalItems()
                  + " việc"
                  + (job.failedItems() > 0
                      ? ", " + job.failedItems() + " việc chưa xử lý được"
                      : "");
          case THAT_BAI -> "Lô duyệt bị gián đoạn, vui lòng mở lại hộp việc và gửi lại";
        };
    return new Job(
        job.id().toString(),
        BatchJob.JOB_TYPE,
        JobStatus.valueOf(status.name()),
        job.progress(),
        message,
        null,
        null,
        job.updatedAt().atOffset(ZoneOffset.UTC));
  }
}
