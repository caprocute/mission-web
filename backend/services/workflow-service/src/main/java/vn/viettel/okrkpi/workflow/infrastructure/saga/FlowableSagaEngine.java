package vn.viettel.okrkpi.workflow.infrastructure.saga;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import vn.viettel.okrkpi.workflow.application.saga.SagaEngine;

/**
 * {@link SagaEngine} trên Flowable 7: tiến trình {@code saga-dieu-phoi} với external worker task
 * (topic {@code saga-buoc}) và nút chờ {@code choSuKien}. Hoàn thành external job của Flowable tạo
 * một job async để đi tiếp, nên vai trò {@code workflow-executor} (async executor bật) là nơi saga
 * thực sự chạy; {@code workflow-api} chỉ khởi động và đánh thức.
 */
public class FlowableSagaEngine implements SagaEngine {

  static final String PROCESS_KEY = "saga-dieu-phoi";
  static final String TOPIC = "saga-buoc";
  static final String WAIT_ACTIVITY = "choSuKien";
  static final String VAR_SAGA_ID = "sagaId";
  static final String VAR_NEXT = "sagaNext";
  static final String RESOURCE = "saga/saga-dieu-phoi.bpmn20.xml";

  /** Số lần giao lại tối đa phía engine; chính sách thử lại thật nằm ở định nghĩa bước. */
  static final int ENGINE_RETRIES = 1_000;

  private final RuntimeService runtime;
  private final ManagementService management;
  private final RepositoryService repository;
  private final String workerId;

  public FlowableSagaEngine(
      RuntimeService runtime,
      ManagementService management,
      RepositoryService repository,
      String workerId) {
    this.runtime = runtime;
    this.management = management;
    this.repository = repository;
    this.workerId = workerId;
  }

  /** Triển khai mẫu BPMN chung; bỏ qua khi nội dung không đổi. */
  public void deploy() {
    repository
        .createDeployment()
        .name(PROCESS_KEY)
        .key(PROCESS_KEY)
        .addClasspathResource(RESOURCE)
        .enableDuplicateFiltering()
        .deploy();
  }

  @Override
  public String launch(UUID sagaId, UUID tenantId, String businessKey) {
    return runtime
        .createProcessInstanceBuilder()
        .processDefinitionKey(PROCESS_KEY)
        .overrideProcessDefinitionTenantId(tenantId.toString())
        .businessKey(businessKey)
        .variable(VAR_SAGA_ID, sagaId.toString())
        .start()
        .getId();
  }

  @Override
  public boolean wake(UUID sagaId) {
    List<Execution> waiting =
        runtime
            .createExecutionQuery()
            .processInstanceBusinessKey("SAGA:" + sagaId)
            .activityId(WAIT_ACTIVITY)
            .list();
    for (Execution execution : waiting) {
      runtime.trigger(execution.getId());
    }
    return !waiting.isEmpty();
  }

  @Override
  public void completeTurn(String turnId, Next next) {
    management
        .createExternalWorkerCompletionBuilder(turnId, workerId)
        .variable(VAR_NEXT, next.name())
        .complete();
  }

  @Override
  public void failTurn(String turnId, String error, Duration backoff) {
    management
        .createExternalWorkerJobFailureBuilder(turnId, workerId)
        .errorMessage(error == null ? "Lỗi không rõ" : error)
        .retries(ENGINE_RETRIES)
        .retryTimeout(backoff)
        .fail();
  }

  /** Lấy và khoá tối đa {@code max} lượt; khoá hết hạn thì worker khác (hay pod mới) lấy lại. */
  public List<AcquiredExternalWorkerJob> acquire(int max, Duration lock) {
    return management
        .createExternalWorkerJobAcquireBuilder()
        .topic(TOPIC, lock)
        .acquireAndLock(max, workerId);
  }

  /** Saga đang dừng ở nút chờ: mã saga và tenant, để quét đối chiếu. */
  public Map<UUID, UUID> waitingSagas(int max) {
    Map<UUID, UUID> out = new java.util.LinkedHashMap<>();
    for (Execution execution :
        runtime
            .createExecutionQuery()
            .processDefinitionKey(PROCESS_KEY)
            .activityId(WAIT_ACTIVITY)
            .listPage(0, max)) {
      Object sagaId = runtime.getVariable(execution.getId(), VAR_SAGA_ID);
      if (sagaId != null && execution.getTenantId() != null) {
        out.put(UUID.fromString(sagaId.toString()), UUID.fromString(execution.getTenantId()));
      }
    }
    return out;
  }

  public String workerId() {
    return workerId;
  }
}
