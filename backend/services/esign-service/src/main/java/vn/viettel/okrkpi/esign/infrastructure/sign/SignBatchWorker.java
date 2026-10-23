package vn.viettel.okrkpi.esign.infrastructure.sign;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.esign.application.sign.EsignService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker ký lô {@code esign-batch-worker} (DV-10-06, ADR-31): mỗi chu kỳ lấy một trang tài liệu chờ
 * ký của các lô đã được xác nhận, ký song song có giới hạn ({@code parallelism}, để HSM hoặc nhà
 * cung cấp không bị quá tải), rồi đóng các lô đã hết tài liệu chờ. Nhiều bản sao chạy song song an
 * toàn nhờ khoá dòng {@code SKIP LOCKED} trên từng tài liệu; KEDA co giãn số bản sao theo metric
 * {@code esign_batch_queue_items}.
 *
 * <p>Metric: {@code esign_batch_queue_items} (tài liệu chờ ký), {@code esign_batch_items_total}
 * theo {@code outcome} (signed, retry, failed), {@code esign_batch_sign_seconds} (thời gian ký một
 * tài liệu), {@code esign_batch_signer_utilization} (tỷ lệ luồng ký đang bận trên giới hạn, ước
 * lượng phần năng lực HSM đang dùng, suy luận).
 */
public class SignBatchWorker implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(SignBatchWorker.class);

  private final BatchItemScanner scanner;
  private final EsignService service;
  private final int scanSize;
  private final int parallelism;
  private final Semaphore slots;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Map<EsignService.ItemOutcome, Counter> outcomes =
      new EnumMap<>(EsignService.ItemOutcome.class);
  private final Timer signTimer;

  public SignBatchWorker(
      BatchItemScanner scanner,
      EsignService service,
      int scanSize,
      int parallelism,
      MeterRegistry meters) {
    if (parallelism < 1 || scanSize < 1) {
      throw new IllegalArgumentException("parallelism và scan-size của worker ký lô phải >= 1");
    }
    this.scanner = scanner;
    this.service = service;
    this.scanSize = scanSize;
    this.parallelism = parallelism;
    this.slots = new Semaphore(parallelism);
    for (EsignService.ItemOutcome o : EsignService.ItemOutcome.values()) {
      if (o != EsignService.ItemOutcome.SKIPPED) {
        outcomes.put(
            o,
            Counter.builder("esign.batch.items")
                .description("Tài liệu của lô đã xử lý theo kết quả")
                .tag("outcome", o.name().toLowerCase(java.util.Locale.ROOT))
                .register(meters));
      }
    }
    this.signTimer =
        Timer.builder("esign.batch.sign")
            .description("Thời gian ký một tài liệu của lô")
            .register(meters);
    Gauge.builder("esign.batch.queue.items", scanner, BatchItemScanner::queueLength)
        .description("Tài liệu chờ ký của các lô đã được xác nhận (co giãn worker)")
        .register(meters);
    Gauge.builder("esign.batch.signer.utilization", inFlight, n -> n.get() / (double) parallelism)
        .description("Tỷ lệ luồng ký đang bận trên giới hạn song song")
        .register(meters);
  }

  /** Một chu kỳ; trả số tài liệu đã xử lý (không tính tài liệu bị worker khác giữ). */
  @Scheduled(fixedDelayString = "${okrkpi.esign.batch-worker.poll-interval:1s}")
  public int poll() {
    List<BatchItemScanner.Pending> pending = scanner.scan(scanSize);
    if (pending.isEmpty()) {
      return 0;
    }
    Map<UUID, UUID> touched = new LinkedHashMap<>();
    List<Future<EsignService.ItemOutcome>> running = new ArrayList<>();
    for (BatchItemScanner.Pending p : pending) {
      touched.put(p.requestId(), p.tenantId());
      try {
        slots.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      running.add(executor.submit(() -> signOne(p)));
    }
    int processed = 0;
    for (Future<EsignService.ItemOutcome> f : running) {
      try {
        if (f.get() != EsignService.ItemOutcome.SKIPPED) {
          processed++;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return processed;
      } catch (ExecutionException e) {
        LOG.warn("Ký tài liệu của lô lỗi: {}", e.getCause().toString());
      }
    }
    touched.forEach(
        (request, tenant) -> {
          try {
            TenantContext.run(TenantId.of(tenant), () -> service.finishBatch(request));
          } catch (RuntimeException e) {
            LOG.warn("Đóng lô ký {} lỗi: {}", request, e.toString());
          }
        });
    return processed;
  }

  private EsignService.ItemOutcome signOne(BatchItemScanner.Pending p) {
    inFlight.incrementAndGet();
    try {
      EsignService.ItemOutcome outcome =
          signTimer.recordCallable(
              () ->
                  TenantContext.call(
                      TenantId.of(p.tenantId()), () -> service.signBatchItem(p.itemId())));
      Counter counter = outcomes.get(outcome);
      if (counter != null) {
        counter.increment();
      }
      return outcome;
    } catch (Exception e) {
      LOG.warn("Ký tài liệu {} của lô {} lỗi: {}", p.itemId(), p.requestId(), e.toString());
      return EsignService.ItemOutcome.SKIPPED;
    } finally {
      inFlight.decrementAndGet();
      slots.release();
    }
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
