package vn.viettel.okrkpi.analytics.infrastructure.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobQueryService;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code GET /api/v1/jobs/{id}} của SVC-15: theo dõi tác vụ xuất (baseline mục 13); chỉ người khởi
 * tạo xem được, người khác nhận 404.
 */
@RestController
@RequestMapping("/api/v1")
public class JobsController {

  private final JobQueryService jobs;

  public JobsController(JobQueryService jobs) {
    this.jobs = jobs;
  }

  @GetMapping("/jobs/{id}")
  public ResponseEntity<Job> getJob(@PathVariable String id) {
    RoleGate.require("getJob");
    Viewer viewer = Viewers.current();
    ResponseEntity<Job> r =
        Jobs.status(jobs.get(TenantId.of(viewer.tenant()), ActorId.of(viewer.userId()), id));
    return ResponseEntity.status(r.getStatusCode())
        .headers(r.getHeaders())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(r.getBody());
  }
}
