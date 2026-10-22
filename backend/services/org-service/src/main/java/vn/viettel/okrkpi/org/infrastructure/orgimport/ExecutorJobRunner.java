package vn.viettel.okrkpi.org.infrastructure.orgimport;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.org.application.port.BackgroundJobRunner;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * Hàng đợi tác vụ nền trong tiến trình cho nhập Excel (DV-04-05): vài luồng cố định, chạy sau khi giao dịch nhận tệp
 * commit để tác vụ luôn thấy bản ghi {@code org.import_job}. Tác vụ đang chờ mất khi pod dừng và giữ trạng thái
 * {@code CHO}; người dùng nhập lại (suy luận, chưa có cơ chế nhận lại tác vụ dở dang).
 */
@Component
public class ExecutorJobRunner implements BackgroundJobRunner, DisposableBean {

  private final ExecutorService executor;

  public ExecutorJobRunner(@Value("${okrkpi.org.import.workers:2}") int workers) {
    this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(100),
        r -> {
          Thread t = new Thread(r, "org-import");
          t.setDaemon(true);
          return t;
        });
  }

  @Override
  public void submitAfterCommit(UUID tenantId, Runnable work) {
    Runnable task = () -> {
      TenantContextHolder.set(tenantId);
      try {
        work.run();
      } finally {
        TenantContextHolder.clear();
      }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          executor.execute(task);
        }
      });
    } else {
      executor.execute(task);
    }
  }

  @Override
  public void destroy() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);
  }
}
