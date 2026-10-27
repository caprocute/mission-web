package vn.viettel.okrkpi.task.infrastructure.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.ApprovePlanVersionRequest;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService;

/**
 * Duyệt kế hoạch có ký số ({@code approvePlanVersion}, SCR-0403, CORE-02-09) và cổng nguồn ký nội
 * bộ mà SVC-10 hỏi khi tạo yêu cầu ký cho nguồn {@code task} ({@code HttpSourceDocumentClient} của
 * esign-service). Cổng nội bộ không có trong hợp đồng công khai; đường dẫn và thân phản hồi theo
 * esign-service (suy luận, wiki trang 14).
 */
@RestController
public class PlanApprovalController {

  static final String KIND = "KE_HOACH";
  static final String FORMAT = "CADES_T";

  private final PlanApprovalService approvals;
  private final TaskCallers callers;

  public PlanApprovalController(PlanApprovalService approvals, TaskCallers callers) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping("/api/v1/plans/{id}/versions/{v}/approve")
  @Idempotent
  @RequiresPermission(operationId = "approvePlanVersion", module = "plans", resourceType = "plan")
  public ResponseEntity<Job> approvePlanVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody ApprovePlanVersionRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    PlanApprovalService.Approver approver = callers.approver();
    PlanApprovalService.ApproveCommand cmd =
        new PlanApprovalService.ApproveCommand(
            approver,
            body == null || body.getSignMethod() == null ? null : body.getSignMethod().getValue(),
            body == null ? null : body.getComment(),
            idempotencyKey,
            bearerToken(authorization));
    PlanApprovalService.Started started =
        found(() -> approvals.approve(TenantContext.require(), id, v, expected, cmd));
    Job job =
        new Job(
            started.signRequestId().toString(),
            PlanApprovalJobs.TYPE,
            JobStatus.CHO,
            0,
            null,
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/jobs/" + started.signRequestId()))
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(job);
  }

  /** Thân phản hồi cổng nguồn ký (khớp {@code HttpSourceDocumentClient.Response} của SVC-10). */
  public record SourceResponse(boolean ready, Set<UUID> signers, List<SourceDoc> documents) {}

  /** Một tài liệu cần ký: băm hex chữ thường. */
  public record SourceDoc(String kind, String ref, String sha256, String format) {}

  @GetMapping("/internal/v1/esign-sources/task/{id}")
  public SourceResponse esignSource(
      @PathVariable("id") UUID versionId,
      @RequestParam(value = "purpose", required = false) String purpose) {
    PlanApprovalService.SignSource source =
        approvals
            .signSource(TenantContext.require(), versionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<SourceDoc> documents =
        source.contentHash() == null
            ? List.of()
            : List.of(
                new SourceDoc(
                    KIND,
                    "task/plan_version/" + versionId,
                    HexFormat.of().formatHex(source.contentHash()),
                    FORMAT));
    return new SourceResponse(source.ready(), source.signers(), documents);
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /** Token Bearer của người duyệt để chuyển tiếp sang SVC-10; thiếu thì trả 401. */
  private static String bearerToken(String authorization) {
    if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return authorization.substring(7).strip();
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập để ký số");
  }
}
