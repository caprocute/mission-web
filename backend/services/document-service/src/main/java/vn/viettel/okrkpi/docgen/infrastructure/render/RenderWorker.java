package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker sinh (DV-11-01): mỗi chu kỳ lấy một lô tác vụ đang chờ (cùng tác vụ đang sinh đã quá hạn
 * thuê), sinh từng tác vụ trong ngữ cảnh tenant của nó; nhiều bản sao (KEDA co giãn theo hàng đợi
 * {@code docgen.render.queue}) chạy song song an toàn nhờ {@code SKIP LOCKED}.
 *
 * <p>DV-11-04: trong một bản sao, các tác vụ của lô chạy song song có giới hạn ({@code
 * concurrency}), đặt theo số pod Gotenberg × số luồng mỗi pod chia cho số bản sao để không dồn
 * Gotenberg quá tải (TL-01 mục SVC-11: 4–6 pod × 2 luồng). Kết nối HTTP tới Gotenberg dùng lại qua
 * pool của {@code java.net.http.HttpClient}. Hàng đợi của executor giới hạn bằng cỡ lô (TL-01 cấm
 * executor không giới hạn); đầy thì luồng gọi tự chạy tác vụ.
 */
public class RenderWorker implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(RenderWorker.class);

  private final PendingJobScanner scanner;
  private final RenderService service;
  private final int batchSize;
  private final Duration lease;
  private final ExecutorService pool;

  public RenderWorker(
      PendingJobScanner scanner, RenderService service, int batchSize, Duration lease) {
    this(scanner, service, batchSize, lease, 1);
  }

  public RenderWorker(
      PendingJobScanner scanner,
      RenderService service,
      int batchSize,
      Duration lease,
      int concurrency) {
    this.scanner = scanner;
    this.service = service;
    this.lease = lease;
    int threads = Math.max(1, concurrency);
    this.batchSize = Math.max(batchSize, threads);
    AtomicInteger seq = new AtomicInteger();
    this.pool =
        threads == 1
            ? null
            : new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.batchSize),
                r -> {
                  Thread t = new Thread(r, "docgen-render-" + seq.incrementAndGet());
                  t.setDaemon(true);
                  return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
  }

  @Scheduled(fixedDelayString = "${okrkpi.docgen.worker.poll-interval:1s}")
  public void poll() {
    List<PendingJobScanner.Pending> batch = scanner.scan(batchSize, lease);
    if (pool == null) {
      batch.forEach(this::process);
      return;
    }
    List<Future<?>> running = new ArrayList<>(batch.size());
    for (PendingJobScanner.Pending pending : batch) {
      running.add(pool.submit(() -> process(pending)));
    }
    for (Future<?> f : running) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException e) {
        LOG.warn("Luồng sinh lỗi: {}", e.getCause().toString());
      }
    }
  }

  private void process(PendingJobScanner.Pending pending) {
    try {
      TenantContext.run(TenantId.of(pending.tenantId()), () -> service.processOnce(pending.id()));
    } catch (RuntimeException e) {
      LOG.warn("Sinh tác vụ {} lỗi: {}", pending.id(), e.toString());
    }
  }

  @Override
  public void close() {
    if (pool != null) {
      pool.shutdownNow();
    }
  }
}
