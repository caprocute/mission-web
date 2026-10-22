package vn.viettel.okrkpi.workflow.infrastructure.saga;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaRepository.ConcurrentSagaUpdateException;

/**
 * Worker saga chạy trên vai trò {@code workflow-executor}: lấy lượt từ engine, chạy lượt trong ngữ
 * cảnh tenant của saga; lượt lỗi được ghi lần thử trong giao dịch mới. Định kỳ quét saga đang ở nút
 * chờ để đánh thức hoặc tính quá hạn.
 */
public class SagaWorker {

  private static final Logger LOG = LoggerFactory.getLogger(SagaWorker.class);
  private static final Duration CONFLICT_BACKOFF = Duration.ofSeconds(1);

  private final FlowableSagaEngine engine;
  private final SagaService service;
  private final int batchSize;
  private final Duration lock;

  public SagaWorker(FlowableSagaEngine engine, SagaService service, int batchSize, Duration lock) {
    this.engine = engine;
    this.service = service;
    this.batchSize = batchSize;
    this.lock = lock;
  }

  @Scheduled(fixedDelayString = "${okrkpi.saga.worker.poll-interval:500ms}")
  public void poll() {
    List<AcquiredExternalWorkerJob> jobs = engine.acquire(batchSize, lock);
    for (AcquiredExternalWorkerJob job : jobs) {
      runOne(job);
    }
  }

  @Scheduled(fixedDelayString = "${okrkpi.saga.worker.reconcile-interval:5s}")
  public void reconcile() {
    for (Map.Entry<UUID, UUID> waiting : engine.waitingSagas(batchSize).entrySet()) {
      try {
        TenantContext.run(
            TenantId.of(waiting.getValue()), () -> service.reconcileWaiting(waiting.getKey()));
      } catch (RuntimeException e) {
        LOG.warn("Đối chiếu saga {} lỗi: {}", waiting.getKey(), e.toString());
      }
    }
  }

  void runOne(AcquiredExternalWorkerJob job) {
    Object raw = job.getVariables().get(FlowableSagaEngine.VAR_SAGA_ID);
    if (raw == null || job.getTenantId() == null) {
      LOG.error("Lượt {} thiếu mã saga hoặc tenant", job.getId());
      return;
    }
    UUID sagaId = UUID.fromString(raw.toString());
    TenantId tenant = TenantId.parse(job.getTenantId());
    TenantContext.run(
        tenant,
        () -> {
          try {
            service.runTurn(sagaId, job.getId());
          } catch (ConcurrentSagaUpdateException e) {
            // Lệnh khác vừa sửa saga (trả lời, điều khiển): giao lại lượt, không tính là lần thử.
            service.releaseTurn(job.getId(), CONFLICT_BACKOFF);
          } catch (RuntimeException e) {
            LOG.warn("Lượt {} của saga {} lỗi: {}", job.getId(), sagaId, e.toString());
            service.recordTurnFailure(sagaId, job.getId(), describe(e));
          }
        });
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
