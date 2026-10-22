package vn.viettel.okrkpi.config.infrastructure.client;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.config.application.port.PeriodLockSaga;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowSagaClient;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** {@link PeriodLockSaga} qua {@link WorkflowSagaClient}; chưa cấu hình SVC-06 thì trả 503. */
public class WorkflowPeriodLockSaga implements PeriodLockSaga {

  private final ObjectProvider<WorkflowSagaClient> client;

  public WorkflowPeriodLockSaga(ObjectProvider<WorkflowSagaClient> client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public UUID start(
      TenantId tenantId, PeriodId periodId, UUID lockOrderId, UUID requestedBy, String key) {
    WorkflowSagaClient c = client.getIfAvailable();
    if (c == null) {
      throw new DependencyUnavailableException(
          "workflow-service",
          DependencyUnavailableException.Reason.IO_ERROR,
          Duration.ofSeconds(30),
          null);
    }
    try {
      return c.startPeriodClose(tenantId, periodId.value(), lockOrderId, requestedBy, key);
    } catch (WorkflowSagaClient.SagaExists e) {
      throw new SagaExistsException(e);
    }
  }
}
