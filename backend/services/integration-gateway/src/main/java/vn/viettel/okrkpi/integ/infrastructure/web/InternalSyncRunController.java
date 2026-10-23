package vn.viettel.okrkpi.integ.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.SyncRunRejectedException;
import vn.viettel.okrkpi.integ.application.SyncRunService;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Kind;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Run;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ lần đồng bộ cho org-service (DV-13-06, SCR-0104 "Đồng bộ ngay", "Chạy lại thông điệp
 * lỗi"; DV-04-07 {@code HttpIntegrationGatewayAdapter}): token dịch vụ vai trò {@link
 * #SERVICE_ROLE}. Mã lần chạy do org-service cấp nên các lô gateway đẩy về {@code
 * /internal/v1/org/sync-runs/{runId}/batches} khớp đúng lần chạy ở SVC-04. Kết nối đang có lần chạy
 * mở trả 409; kết nối không đồng bộ theo lần chạy hoặc chưa cấu hình trả 422 kèm {@code reason}
 * (hợp đồng nội bộ suy luận, ghi ở wiki trang 14).
 */
@RestController
@RequestMapping("/internal/v1/integ/sync-runs")
@ConditionalOnWebApplication
public class InternalSyncRunController {

  /** Vai trò realm của tài khoản dịch vụ org-service. */
  public static final String SERVICE_ROLE = "svc_org_service";

  private final SyncRunService runs;

  public InternalSyncRunController(SyncRunService runs) {
    this.runs = runs;
  }

  public record StartRequest(UUID tenantId, UUID runId, String connector) {}

  public record RetryRequest(UUID tenantId, UUID retryRunId, String connector) {}

  public record RunDto(
      UUID runId,
      String kind,
      String status,
      UUID retryOf,
      int pages,
      int messages,
      int duplicates,
      int deadLetters,
      String error,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt) {

    static RunDto of(Run r) {
      return new RunDto(
          r.id(),
          r.kind().name(),
          r.status().name(),
          r.retryOf(),
          r.pages(),
          r.messages(),
          r.duplicates(),
          r.deadLetters(),
          r.errorDetail(),
          r.requestedAt(),
          r.startedAt(),
          r.finishedAt());
    }
  }

  @PostMapping
  public ResponseEntity<RunDto> start(@RequestBody StartRequest body) {
    if (body == null || body.tenantId() == null || body.runId() == null) {
      throw new IllegalArgumentException("Thiếu tenant hoặc mã lần chạy");
    }
    Run r = runs.request(TenantId.of(body.tenantId()), body.runId(), body.connector(), Kind.NGAY);
    return accepted(r);
  }

  @PostMapping("/{runId}/retry")
  public ResponseEntity<RunDto> retry(@PathVariable UUID runId, @RequestBody RetryRequest body) {
    if (body == null || body.tenantId() == null || body.retryRunId() == null) {
      throw new IllegalArgumentException("Thiếu tenant hoặc mã lần chạy lại");
    }
    Run r = runs.retry(TenantId.of(body.tenantId()), runId, body.retryRunId(), body.connector());
    return accepted(r);
  }

  @GetMapping("/{runId}")
  public RunDto status(
      @PathVariable UUID runId,
      @RequestHeader(name = "X-Tenant-Id", required = false) UUID header,
      @RequestParam(required = false) UUID tenantId) {
    UUID t = header != null ? header : tenantId;
    if (t == null) {
      throw new IllegalArgumentException("Thiếu tenant");
    }
    return runs.status(TenantId.of(t), runId)
        .map(RunDto::of)
        .orElseThrow(() -> new NoSuchElementException("Không có lần đồng bộ này"));
  }

  private static ResponseEntity<RunDto> accepted(Run r) {
    return ResponseEntity.accepted()
        .location(URI.create("/internal/v1/integ/sync-runs/" + r.id()))
        .body(RunDto.of(r));
  }

  @ExceptionHandler(SyncRunRejectedException.class)
  ProblemDetail rejected(SyncRunRejectedException e) {
    HttpStatus status =
        e.reason() == SyncRunRejectedException.Reason.RUN_IN_PROGRESS
                || e.reason() == SyncRunRejectedException.Reason.RUN_NOT_FINISHED
            ? HttpStatus.CONFLICT
            : HttpStatus.UNPROCESSABLE_ENTITY;
    ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
    p.setProperty("reason", e.reason().name());
    return p;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }
}
