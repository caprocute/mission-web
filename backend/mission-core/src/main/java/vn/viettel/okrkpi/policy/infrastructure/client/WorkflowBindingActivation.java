package vn.viettel.okrkpi.policy.infrastructure.client;

import java.util.Objects;
import vn.viettel.okrkpi.policy.application.port.PolicyWorkflowActivation;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bước 4 của saga phát hành (CORE-05-10) qua {@code POST
 * /internal/v1/workflow/bindings/activate|deactivate} của SVC-06 (DV-06-08). Lỗi gọi là 503 để saga
 * thử lại rồi bù trừ.
 */
public class WorkflowBindingActivation implements PolicyWorkflowActivation {

  private final WorkflowBindingClient client;

  public WorkflowBindingActivation(WorkflowBindingClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public void activate(TenantId tenant, PolicyPackVersion version) {
    client.activate(tenant, version.id());
  }

  @Override
  public void deactivate(TenantId tenant, PolicyPackVersion version) {
    client.deactivate(tenant, version.id());
  }
}
