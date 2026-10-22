package vn.viettel.okrkpi.task.infrastructure.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.ListSimilarTasks200Response;
import vn.viettel.okrkpi.contract.tasks.model.SimilarTask;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.similar.SimilarTaskService;

/** {@code listSimilarTasks} của {@code tasks.yaml} (BR-05-06, SCR-0501, SCR-0502). */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskSimilarController {

  private final SimilarTaskService similar;
  private final TaskQueryService queries;
  private final TaskCallers callers;

  public TaskSimilarController(
      SimilarTaskService similar, TaskQueryService queries, TaskCallers callers) {
    this.similar = Objects.requireNonNull(similar, "similar");
    this.queries = Objects.requireNonNull(queries, "queries");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping("/{id}/similar")
  @RequiresPermission(operationId = "listSimilarTasks", module = "tasks", resourceType = "task")
  public ListSimilarTasks200Response listSimilarTasks(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    TenantId tenant = TenantContext.require();
    try {
      // Người gọi phải xem được nhiệm vụ gốc; nhiệm vụ hạn chế không bao giờ có trong kết quả.
      queries.getTask(tenant, id, callers.current());
      return new ListSimilarTasks200Response(
          similar.similarTo(tenant, id, authorization).matches().stream()
              .map(TaskSimilarController::dto)
              .toList());
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /** {@code SimilarTask}; tên đơn vị và người chủ trì do giao diện tra theo mã như các API khác. */
  static SimilarTask dto(SimilarTaskService.Match m) {
    BigDecimal progress = m.row().progressPct() == null ? BigDecimal.ZERO : m.row().progressPct();
    return new SimilarTask(
        m.row().id(),
        m.row().code(),
        m.row().title(),
        TaskStatus.fromValue(m.row().status()),
        m.similarity(),
        "",
        "",
        m.row().dueDate(),
        progress.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue());
  }
}
