package vn.viettel.okrkpi.analytics.infrastructure.export;

import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import vn.viettel.okrkpi.analytics.application.ExportJob;
import vn.viettel.okrkpi.analytics.application.ExportService;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nguồn {@code getJob} của SVC-15: tác vụ xuất (loại {@code EXPORT}). */
public final class ExportJobSource implements JobSource {

  private final ExportService exports;

  ExportJobSource(ExportService exports) {
    this.exports = exports;
  }

  @Override
  public String name() {
    return "analytics.export";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    return exports
        .job(tenant.value(), jobId)
        .map(j -> new TrackedJob(toJob(j), ActorId.of(j.initiator()), false));
  }

  public static Job toJob(ExportJob j) {
    ProblemDetail problem = null;
    if (j.status() == ExportJob.Status.THAT_BAI) {
      problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, j.message());
      problem.setTitle("Chưa xuất được tệp");
    }
    return new Job(
        j.id(),
        ExportJob.TYPE,
        JobStatus.valueOf(j.status().name()),
        j.progress(),
        j.message(),
        j.resultLocation(),
        problem,
        j.updatedAt() == null ? null : j.updatedAt().atOffset(ZoneOffset.UTC));
  }
}
