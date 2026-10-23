package vn.viettel.okrkpi.task.infrastructure.listener;

import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionReturnedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionSubmittedEvent;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalBridge;

/**
 * Nghe sự kiện gửi duyệt, trả lại, duyệt kế hoạch (CORE-02-08) sau khi giao dịch phát sự kiện đã
 * commit. Sự kiện nằm trong {@code modulith.event_publication} tới khi xử lý xong, nên SVC-06 tạm
 * gián đoạn thì lần khởi động sau phát lại; khởi tạo idempotent nên phát lại không sinh tiến trình
 * thứ hai.
 */
public class PlanApprovalListener {

  private final PlanApprovalBridge bridge;

  public PlanApprovalListener(PlanApprovalBridge bridge) {
    this.bridge = Objects.requireNonNull(bridge, "bridge");
  }

  @ApplicationModuleListener
  public void onSubmitted(PlanVersionSubmittedEvent event) {
    TenantContext.run(event.tenantId(), () -> bridge.onSubmitted(event));
  }

  @ApplicationModuleListener
  public void onReturned(PlanVersionReturnedEvent event) {
    TenantContext.run(event.tenantId(), () -> bridge.onReturned(event));
  }

  @ApplicationModuleListener
  public void onApproved(PlanVersionApprovedEvent event) {
    TenantContext.run(event.tenantId(), () -> bridge.onApproved(event));
  }
}
