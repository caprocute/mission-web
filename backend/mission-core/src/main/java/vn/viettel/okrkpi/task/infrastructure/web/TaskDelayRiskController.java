package vn.viettel.okrkpi.task.infrastructure.web;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.DelayRisk;
import vn.viettel.okrkpi.contract.tasks.model.TaskDelayRiskInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService;
import vn.viettel.okrkpi.task.domain.lifecycle.DelayForecast;

/**
 * {@code setTaskDelayRisk} của {@code tasks.yaml} (CORE-02-26): cổng nội bộ để bộ dự báo SVC-15
 * (DV-15-10) ghi dự báo nguy cơ trễ hạn. Token người dùng bị từ chối 403 ngay cả khi OPA cho qua.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskDelayRiskController {

  private final TaskDelayRiskService risks;
  private final TaskCallers callers;

  public TaskDelayRiskController(TaskDelayRiskService risks, TaskCallers callers) {
    this.risks = Objects.requireNonNull(risks, "risks");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PutMapping("/{id}/delay-risk")
  @Idempotent
  @RequiresPermission(operationId = "setTaskDelayRisk", module = "tasks", resourceType = "task")
  public ResponseEntity<Void> setTaskDelayRisk(
      @PathVariable("id") UUID id, @RequestBody(required = false) TaskDelayRiskInput body) {
    if (!callers.serviceClient()) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Chỉ bộ dự báo của hệ thống được ghi dự báo trễ hạn");
    }
    TenantId tenant = TenantContext.require();
    TaskDelayRiskService.ForecastInput input =
        body == null
            ? null
            : new TaskDelayRiskService.ForecastInput(
                body.getLevel() == null ? null : body.getLevel().getValue(),
                body.getNeededWorkingDays(),
                body.getExplanation(),
                body.getComputedAt() == null ? null : body.getComputedAt().toInstant(),
                body.getBasedOnProgressId());
    try {
      risks.applyForecast(tenant, id, input);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.noContent().build();
  }

  /** Lược đồ {@code DelayRisk} của chi tiết nhiệm vụ. */
  static Optional<DelayRisk> view(Optional<DelayForecast> forecast) {
    return forecast.map(
        f ->
            new DelayRisk(
                DelayRisk.LevelEnum.fromValue(f.level()), f.neededWorkingDays(), f.explanation()));
  }
}
