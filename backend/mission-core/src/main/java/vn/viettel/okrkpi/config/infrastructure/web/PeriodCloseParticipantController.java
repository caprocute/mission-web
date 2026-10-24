package vn.viettel.okrkpi.config.infrastructure.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.config.application.PeriodCloseParticipantService;
import vn.viettel.okrkpi.config.application.PeriodCloseParticipantService.Command;
import vn.viettel.okrkpi.config.domain.IllegalPeriodTransitionException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cổng nội bộ để saga chốt kỳ của SVC-06 gọi MOD-08 (DV-06-06, QA-06). Không nằm trong hợp đồng
 * công khai; đường dẫn suy luận (wiki trang 14), tenant đọc từ token dịch vụ kèm {@code
 * X-Tenant-Id} như mọi cổng {@code /internal/**}:
 *
 * <ul>
 *   <li>{@code POST /internal/v1/config/periods/{periodId}/close/preconditions}
 *   <li>{@code PUT /internal/v1/config/periods/{periodId}/close/jobs/{jobId}} giao lệnh
 *   <li>{@code GET /internal/v1/config/periods/{periodId}/close/jobs/{jobId}?command=} trạng thái
 *   <li>{@code POST /internal/v1/config/periods/{periodId}/close/undo} bù trừ
 * </ul>
 */
@RestController
@RequestMapping("/internal/v1/config/periods/{periodId}/close")
public class PeriodCloseParticipantController {

  private final PeriodCloseParticipantService service;

  public PeriodCloseParticipantController(PeriodCloseParticipantService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Thân lệnh: saga, lệnh khoá và (khi bù trừ) lý do. */
  public record CloseCommand(
      String command, UUID sagaId, UUID lockOrderId, String undoKey, String reason) {}

  /** Thân kiểm tiền điều kiện. */
  public record PreconditionQuery(UUID sagaId, UUID lockOrderId) {}

  @PostMapping("/preconditions")
  public PeriodCloseParticipantService.Report preconditions(
      @PathVariable UUID periodId, @RequestBody PreconditionQuery body) {
    return service.preconditions(
        TenantContext.require(), PeriodId.of(periodId), body.lockOrderId(), body.sagaId());
  }

  @PutMapping("/jobs/{jobId}")
  public ResponseEntity<Map<String, Object>> submit(
      @PathVariable UUID periodId, @PathVariable UUID jobId, @RequestBody CloseCommand body) {
    Command command = Command.valueOf(body.command());
    service.submit(
        TenantContext.require(), PeriodId.of(periodId), command, body.sagaId(), body.lockOrderId());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(job(jobId, command));
  }

  @GetMapping("/jobs/{jobId}")
  public Map<String, Object> state(
      @PathVariable UUID periodId, @PathVariable UUID jobId, @RequestParam String command) {
    return job(jobId, Command.valueOf(command), periodId);
  }

  @PostMapping("/undo")
  public ResponseEntity<Void> undo(@PathVariable UUID periodId, @RequestBody CloseCommand body) {
    service.undo(
        TenantContext.require(),
        PeriodId.of(periodId),
        Command.valueOf(body.command()),
        body.sagaId(),
        body.lockOrderId(),
        body.reason());
    return ResponseEntity.noContent().build();
  }

  private Map<String, Object> job(UUID jobId, Command command, UUID periodId) {
    PeriodCloseParticipantService.JobStatus status =
        service.state(TenantContext.require(), PeriodId.of(periodId), command);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", jobId.toString());
    out.put("type", command.name());
    out.put("status", status.name());
    out.put("progress", status == PeriodCloseParticipantService.JobStatus.HOAN_TAT ? 100 : 0);
    return out;
  }

  private static Map<String, Object> job(UUID jobId, Command command) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", jobId.toString());
    out.put("type", command.name());
    out.put("status", PeriodCloseParticipantService.JobStatus.HOAN_TAT.name());
    out.put("progress", 100);
    return out;
  }

  @ExceptionHandler(IllegalPeriodTransitionException.class)
  ResponseEntity<ProblemDetail> conflict(IllegalPeriodTransitionException e) {
    return problem(HttpStatus.CONFLICT, "Kỳ không ở trạng thái cho lệnh này.");
  }

  @ExceptionHandler({NoSuchElementException.class, IllegalArgumentException.class})
  ResponseEntity<ProblemDetail> notFound(RuntimeException e) {
    return problem(
        e instanceof NoSuchElementException ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
        e instanceof NoSuchElementException
            ? "Không có kỳ hoặc lệnh khoá kỳ."
            : "Lệnh không hợp lệ.");
  }

  /** Cổng nội bộ không có mã lỗi của hợp đồng: chỉ trạng thái HTTP và mô tả (RFC 9457). */
  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create("about:blank"));
    return ResponseEntity.status(status).body(pd);
  }
}
