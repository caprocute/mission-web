package vn.viettel.okrkpi.task.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.BlockerCauseCategory;
import vn.viettel.okrkpi.contract.tasks.model.BlockerReport;
import vn.viettel.okrkpi.contract.tasks.model.BlockerStatus;
import vn.viettel.okrkpi.contract.tasks.model.CauseKind;
import vn.viettel.okrkpi.contract.tasks.model.ImpactLevel;
import vn.viettel.okrkpi.contract.tasks.model.TaskBlocker;
import vn.viettel.okrkpi.contract.tasks.model.TaskBlockerEscalateRequest;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.blocker.TaskBlockerService;
import vn.viettel.okrkpi.task.application.blocker.TaskBlockerService.Result;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers.BlockerRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;

/**
 * {@code reportTaskBlocker}, {@code handleTaskBlocker}, {@code escalateTaskBlocker} của {@code
 * tasks.yaml} (QT-06, SCR-0504, SCR-0601). {@code handleTaskBlocker} nhận JSON Merge Patch: trường
 * vắng giữ nguyên, trường {@code null} xoá giá trị.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskBlockerController {

  private final TaskBlockerService blockers;
  private final TaskTransitionService transitions;
  private final TaskCallers callers;

  public TaskBlockerController(
      TaskBlockerService blockers, TaskTransitionService transitions, TaskCallers callers) {
    this.blockers = Objects.requireNonNull(blockers, "blockers");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping("/{id}/blockers")
  @Idempotent
  @RequiresPermission(operationId = "reportTaskBlocker", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDetail> reportTaskBlocker(
      @PathVariable("id") UUID id, @RequestBody BlockerReport body) {
    TenantId tenant = TenantContext.require();
    if (body == null) {
      throw invalid("content", "Thiếu nội dung điểm nghẽn");
    }
    Result r =
        found(
            () ->
                blockers.reportBlocker(
                    tenant,
                    id,
                    new TaskBlockerService.Report(
                        body.getContent(),
                        value(body.getCauseCategory()),
                        body.getCauseClaimed() == null ? null : body.getCauseClaimed().getValue(),
                        body.getImpactLevel() == null ? null : body.getImpactLevel().getValue(),
                        body.getResolveBy()),
                    callers.current()));
    TaskDetail dto =
        TaskTransitionController.detail(r.task(), transitions.stateLog(tenant, r.task()));
    dto.setBlockers(
        blockers.listOf(tenant, r.task()).stream().map(TaskBlockerController::dto).toList());
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + id))
        .eTag(ETags.of(r.task().rowVersion()))
        .body(dto);
  }

  @PatchMapping("/{id}/blockers/{blockerId}")
  @Idempotent
  @RequiresPermission(operationId = "handleTaskBlocker", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskBlocker> handleTaskBlocker(
      @PathVariable("id") UUID id,
      @PathVariable("blockerId") UUID blockerId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    if (body == null || !body.isObject()) {
      throw invalid("status", "Nội dung xử lý điểm nghẽn không hợp lệ");
    }
    TaskBlockerService.Patch patch =
        new TaskBlockerService.Patch(
            body.has("handlerPersonId"),
            uuid(body.get("handlerPersonId"), "handlerPersonId"),
            body.has("resolveBy"),
            date(body.get("resolveBy")),
            text(body.get("status"), "status"),
            text(body.get("resolutionNote"), "resolutionNote"));
    Result r =
        found(
            () ->
                blockers.handleBlocker(tenant, id, blockerId, expected, patch, callers.current()));
    return ETags.ok(dto(r.blocker()), r.blocker().rowVersion());
  }

  @PostMapping("/{id}/blockers/{blockerId}/escalate")
  @Idempotent
  @RequiresPermission(operationId = "escalateTaskBlocker", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskBlocker> escalateTaskBlocker(
      @PathVariable("id") UUID id,
      @PathVariable("blockerId") UUID blockerId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody TaskBlockerEscalateRequest body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    if (body == null) {
      throw invalid("note", "Thiếu ghi chú chuyển cấp trên");
    }
    Result r =
        found(
            () ->
                blockers.escalateBlocker(
                    tenant,
                    id,
                    blockerId,
                    expected,
                    body.getTargetPersonId(),
                    body.getTargetUnitId(),
                    body.getNote(),
                    callers.current()));
    return ETags.ok(dto(r.blocker()), r.blocker().rowVersion());
  }

  static TaskBlocker dto(BlockerRow b) {
    return new TaskBlocker(
            b.id(),
            b.code() == null ? "" : b.code(),
            b.content(),
            BlockerCauseCategory.fromValue(b.causeCategory()),
            CauseKind.fromValue(b.causeClaimed() == null ? "KHACH_QUAN" : b.causeClaimed()),
            ImpactLevel.fromValue(b.impactLevel()),
            BlockerStatus.fromValue(b.status()),
            b.createdAt().atOffset(ZoneOffset.UTC))
        .resolveBy(b.resolveBy())
        .rowVersion((int) b.rowVersion());
  }

  private static String value(BlockerCauseCategory c) {
    return c == null ? null : c.getValue();
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static UUID uuid(JsonNode n, String field) {
    if (n == null || n.isNull()) {
      return null;
    }
    try {
      return UUID.fromString(n.asText());
    } catch (IllegalArgumentException e) {
      throw invalid(field, "Người xử lý không hợp lệ");
    }
  }

  private static LocalDate date(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    try {
      return LocalDate.parse(n.asText());
    } catch (DateTimeParseException e) {
      throw invalid("resolveBy", "Hạn xử lý không hợp lệ");
    }
  }

  private static String text(JsonNode n, String field) {
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isTextual()) {
      throw invalid(field, "Giá trị không hợp lệ");
    }
    return n.asText();
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
