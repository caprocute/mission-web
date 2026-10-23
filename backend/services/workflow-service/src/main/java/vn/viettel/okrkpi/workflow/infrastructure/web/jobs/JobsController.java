package vn.viettel.okrkpi.workflow.infrastructure.web.jobs;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobQueryService;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.infrastructure.web.CurrentCaller;
import vn.viettel.okrkpi.workflow.infrastructure.web.jobs.api.JobsApi;
import vn.viettel.okrkpi.workflow.infrastructure.web.jobs.model.GetJob200Response;
import vn.viettel.okrkpi.workflow.infrastructure.web.jobs.model.GetJob200ResponseProblem;

/** {@code GET /api/v1/jobs/{id}} của SVC-06: theo dõi saga đã khởi động (baseline mục 13). */
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
    dto.setResultLocation(job.resultLocation());
    dto.setProblem(problem(job.problem()));
    dto.setUpdatedAt(job.updatedAt());
    return ok.body(dto);
  }

  /** Problem của tác vụ thất bại (ví dụ {@code PERIOD-422-PRECONDITION} của saga chốt kỳ). */
  private static GetJob200ResponseProblem problem(ProblemDetail pd) {
    if (pd == null) {
      return null;
    }
    GetJob200ResponseProblem dto =
        new GetJob200ResponseProblem(
            pd.getType() == null ? null : pd.getType().toString(), pd.getTitle(), pd.getStatus());
    dto.setDetail(pd.getDetail());
    Map<String, Object> props = pd.getProperties();
    if (props != null) {
      dto.setCode(props.get("code") instanceof String code ? code : null);
      dto.setMsg(props.get("msg") instanceof String msg ? msg : null);
    }
    return dto;
  }
}
