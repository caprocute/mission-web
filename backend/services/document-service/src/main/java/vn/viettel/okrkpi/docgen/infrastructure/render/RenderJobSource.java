package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.shared.job.JobIds;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nguồn {@code getJob} cho lệnh sinh: mã tác vụ là {@code doc-<render_job.request_id>}. */
public class RenderJobSource implements JobSource {

  private final RenderService service;

  public RenderJobSource(RenderService service) {
    this.service = service;
  }

  @Override
  public String name() {
    return "docgen.render_job";
  }

  @Override
  public Optional<TrackedJob> find(TenantId tenant, String jobId) {
    Optional<UUID> parsed = JobIds.parse(RenderService.JOB_PREFIX, jobId);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    UUID id = parsed.get();
    return service
        .job(id)
        .flatMap(
            job -> service.initiator(id).map(by -> new TrackedJob(job, ActorId.of(by), false)));
  }
}
