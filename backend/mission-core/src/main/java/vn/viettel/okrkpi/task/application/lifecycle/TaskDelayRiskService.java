package vn.viettel.okrkpi.task.application.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDelayRisks;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.lifecycle.DelayForecast;

/**
 * Cổng ghi dự báo nguy cơ trễ hạn cho bộ dự báo SVC-15 ({@code setTaskDelayRisk}, CORE-02-26) và
 * đọc lại cho chi tiết nhiệm vụ. Không phát sự kiện nghiệp vụ, không đổi {@code row_version}.
 */
public class TaskDelayRiskService {

  static final String TX = "taskTransactionManager";
  static final String STALE = "TASK-409-STALE-DELAY-RISK";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";

  private final TaskLifecycleRepository tasks;
  private final TaskDelayRisks risks;

  public TaskDelayRiskService(TaskLifecycleRepository tasks, TaskDelayRisks risks) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.risks = Objects.requireNonNull(risks, "risks");
  }

  /** Dữ liệu dự báo người gọi gửi lên, chưa kiểm. */
  public record ForecastInput(
      String level,
      Integer neededWorkingDays,
      String explanation,
      Instant computedAt,
      UUID basedOnProgressId) {}

  /**
   * Lưu dự báo; nhiệm vụ đã đóng hoặc tạm dừng trả {@value #INVALID_STATE}, kết quả cũ hơn dự báo
   * đang lưu trả {@value #STALE}.
   */
  @Transactional(TX)
  public void applyForecast(TenantId tenant, UUID taskId, ForecastInput input) {
    DelayForecast forecast = validate(input);
    TaskRow task = tasks.lock(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (!DelayForecast.accepts(task.status())) {
      throw new BusinessException(
          INVALID_STATE, "Nhiệm vụ đã kết thúc hoặc đang tạm dừng, không nhận dự báo trễ hạn");
    }
    if (!forecast.newerThan(risks.storedAt(tenant, task.periodBucket(), taskId).orElse(null))) {
      throw new BusinessException(STALE, "Đã có dự báo trễ hạn mới hơn cho nhiệm vụ này");
    }
    risks.store(tenant, task.periodBucket(), taskId, forecast);
  }

  /** Dự báo đang lưu của nhiệm vụ đọc được; rỗng khi chưa có hoặc nhiệm vụ đã đóng. */
  @Transactional(value = TX, readOnly = true)
  public Optional<DelayForecast> find(TenantId tenant, TaskRow task) {
    if (task.delayRisk() == null || !DelayForecast.accepts(task.status())) {
      return Optional.empty();
    }
    return risks.find(tenant, task.periodBucket(), task.id());
  }

  private static DelayForecast validate(ForecastInput in) {
    if (in == null) {
      throw invalid("level");
    }
    try {
      return new DelayForecast(
          in.level(),
          in.neededWorkingDays() == null ? -1 : in.neededWorkingDays(),
          in.explanation(),
          in.computedAt(),
          in.basedOnProgressId());
    } catch (NullPointerException e) {
      throw invalid("computedAt");
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
  }

  private static BusinessException invalid(String field) {
    String message = "Dữ liệu dự báo không hợp lệ";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
