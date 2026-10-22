package vn.viettel.okrkpi.workflow.infrastructure.batch;

import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.BatchDispatcher;

/**
 * {@link BatchDispatcher} trên một {@link ThreadPoolExecutor} có hàng đợi giới hạn (TL-01 "Cô lập
 * tài nguyên theo phụ thuộc"): việc của lô chạy sau khi giao dịch tạo tác vụ cam kết, mang ngữ cảnh
 * bảo mật (để OPA thấy người gửi) và tenant của người gửi.
 */
@Component
public class ExecutorBatchDispatcher implements BatchDispatcher, DisposableBean {

  private final ThreadPoolExecutor executor;

  public ExecutorBatchDispatcher(BatchExecutorFactory factory) {
    this.executor = factory.create();
  }

  @Override
  public void dispatchAfterCommit(Runnable work) {
    Optional<TenantId> tenant = TenantContext.current();
    Runnable withTenant =
        () -> {
          if (tenant.isPresent()) {
            TenantContext.run(tenant.get(), work);
          } else {
            work.run();
          }
        };
    Runnable task =
        new DelegatingSecurityContextRunnable(withTenant, SecurityContextHolder.getContext());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
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
  public void destroy() {
    executor.shutdown();
  }
}
