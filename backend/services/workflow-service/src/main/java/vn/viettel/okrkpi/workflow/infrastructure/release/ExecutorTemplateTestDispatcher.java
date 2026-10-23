package vn.viettel.okrkpi.workflow.infrastructure.release;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestDispatcher;

/**
 * Chạy lần test ở một luồng nền với hàng đợi giới hạn: engine thử chạy tuần tự nên một luồng là đủ;
 * hàng đợi đầy thì từ chối (lần chạy ở lại {@code CHO}, người soạn chạy lại sau).
 */
public class ExecutorTemplateTestDispatcher implements TemplateTestDispatcher, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ExecutorTemplateTestDispatcher.class);

  private final ThreadPoolExecutor executor;

  public ExecutorTemplateTestDispatcher(int queueCapacity) {
    AtomicInteger n = new AtomicInteger();
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
            r -> {
              Thread t = new Thread(r, "mau-luong-test-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Override
  public void dispatch(Runnable work) {
    executor.execute(
        () -> {
          try {
            work.run();
          } catch (RuntimeException e) {
            log.warn("Lần chạy test mẫu luồng lỗi: {}", e.toString());
          }
        });
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
