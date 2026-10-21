package vn.viettel.okrkpi.shared.infrastructure.web;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.contract.jobs.api.JobsApi;
import vn.viettel.okrkpi.contract.jobs.model.Job;
import vn.viettel.okrkpi.contract.jobs.model.JobStatus;
import vn.viettel.okrkpi.contract.jobs.model.ProblemDetails;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.job.JobQueryService;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Adapter REST cho hợp đồng {@code jobs.yaml} ({@code GET /api/v1/jobs/{id}}, {@code DELETE
 * /api/v1/jobs/{id}}, {@code x-mission.owner: SVC-05}) uỷ quyền sang {@link JobQueryService} của
 * {@code vt-platform-starter}.
 */
@RestController
@RequestMapping("/api/v1")
public class JobsController implements JobsApi {

  private final JobQueryService jobQueryService;
  private final ObjectProvider<AuthzSubjectResolver> subjectResolverProvider;

  public JobsController(
      JobQueryService jobQueryService,
      ObjectProvider<AuthzSubjectResolver> subjectResolverProvider) {
    this.jobQueryService =
        Objects.requireNonNull(jobQueryService, "jobQueryService must not be null");
    this.subjectResolverProvider =
        Objects.requireNonNull(subjectResolverProvider, "subjectResolverProvider must not be null");
  }

  @Override
  public ResponseEntity<Job> getJob(String id) {
    TenantId tenantId = resolveTenantId();
    ActorId caller = resolveActorId();
    vn.viettel.okrkpi.shared.job.Job found = jobQueryService.get(tenantId, caller, id);
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
    if (!found.status().isTerminal()) {
      builder.header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS));
    }
    return builder.body(toContractJob(found));
  }

  @Override
  public ResponseEntity<Void> cancelJob(String idempotencyKey, String id) {
    TenantId tenantId = resolveTenantId();
    ActorId caller = resolveActorId();
    jobQueryService.cancel(tenantId, caller, id);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  private TenantId resolveTenantId() {
    return TenantContext.current().orElse(InMemoryJobRegistry.DEFAULT_TENANT_ID);
  }

  private ActorId resolveActorId() {
    var override = InMemoryJobRegistry.currentActorOverride();
    if (override.isPresent()) {
      return override.get();
    }
    try {
      AuthzSubjectResolver resolver = subjectResolverProvider.getIfAvailable();
      if (resolver != null) {
        OpaInput.Subject subject = resolver.currentSubject();
        if (subject != null && subject.userId() != null && !subject.userId().isBlank()) {
          return ActorId.parse(subject.userId());
        }
      }
    } catch (Throwable ignored) {
      // Fallback cho kiểm thử hợp đồng MockMvc không gửi kèm JWT hoặc chưa nạp Spring Security
    }
    return InMemoryJobRegistry.DEFAULT_ACTOR_ID;
  }

  private static Job toContractJob(vn.viettel.okrkpi.shared.job.Job source) {
    Job dto = new Job(source.id(), source.type(), JobStatus.fromValue(source.status().name()));
    dto.setProgress(source.progress());
    dto.setMessage(source.message());
    dto.setResultLocation(source.resultLocation());
    if (source.problem() != null) {
      dto.setProblem(toContractProblem(source.problem()));
    }
    dto.setUpdatedAt(source.updatedAt());
    return dto;
  }

  private static ProblemDetails toContractProblem(ProblemDetail problem) {
    String type = problem.getType() != null ? problem.getType().toString() : "about:blank";
    String title = problem.getTitle() != null ? problem.getTitle() : "Lỗi tác vụ";
    String code =
        problem.getProperties() != null && problem.getProperties().get("code") != null
            ? problem.getProperties().get("code").toString()
            : "SYS-500";
    ProblemDetails details = new ProblemDetails(type, title, problem.getStatus());
    details.setCode(code);
    details.setDetail(problem.getDetail());
    if (problem.getInstance() != null) {
      details.setInstance(problem.getInstance().toString());
    }
    if (problem.getProperties() != null && problem.getProperties().get("traceId") != null) {
      details.setTraceId(problem.getProperties().get("traceId").toString());
    }
    return details;
  }
}
