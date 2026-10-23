package vn.viettel.okrkpi.scoring.infrastructure.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.scoring.infrastructure.web.jobs.api.JobsApi;
import vn.viettel.okrkpi.scoring.infrastructure.web.jobs.model.GetJob200Response;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobQueryService;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** {@code GET /api/v1/jobs/{id}} của SVC-07: theo dõi tác vụ tính (baseline mục 13, DV-07-06). */
@RestController
@RequestMapping("/api/v1")
public class JobsController implements JobsApi {

  private final JobQueryService jobs;

  public JobsController(JobQueryService jobs) {
    this.jobs = jobs;
  }

  @Override
  public ResponseEntity<GetJob200Response> getJob(String id) {
    Job job = jobs.get(TenantContext.require(), ActorId.of(CurrentCaller.actor()), id);
    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
    if (!job.status().isTerminal()) {
      ok.header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS));
    }
    GetJob200Response dto =
        new GetJob200Response(
            job.id(), job.type(), GetJob200Response.StatusEnum.fromValue(job.status().name()));
    dto.setProgress(job.progress());
    dto.setMessage(job.message());
    dto.setUpdatedAt(job.updatedAt());
    return ok.body(dto);
  }
}
