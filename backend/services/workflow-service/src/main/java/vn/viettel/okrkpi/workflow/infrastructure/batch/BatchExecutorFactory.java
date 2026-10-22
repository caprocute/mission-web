package vn.viettel.okrkpi.workflow.infrastructure.batch;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dựng executor của lô duyệt: số luồng và hàng đợi giới hạn theo cấu hình, hàng đợi đầy thì từ chối
 * ({@code AbortPolicy}) để yêu cầu nhận lỗi thay vì dồn việc vô hạn.
 */
@Component
public class BatchExecutorFactory {

  private final int threads;
  private final int queueCapacity;

  public BatchExecutorFactory(
      @Value("${mission.workflow.batch.threads:2}") int threads,
      @Value("${mission.workflow.batch.queue-capacity:50}") int queueCapacity) {
    this.threads = Math.max(1, threads);
    this.queueCapacity = Math.max(1, queueCapacity);
  }

  ThreadPoolExecutor create() {
    AtomicInteger seq = new AtomicInteger();
    ThreadFactory factory =
        r -> {
          Thread t = new Thread(r, "wf-batch-" + seq.incrementAndGet());
          t.setDaemon(true);
          return t;
        };
    return new ThreadPoolExecutor(
        threads,
        threads,
        60,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        factory,
        new ThreadPoolExecutor.AbortPolicy());
  }
}
