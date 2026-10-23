package vn.viettel.okrkpi.task.infrastructure.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowApprovalClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalProcess;

/**
 * {@link PlanApprovalProcess} qua {@link WorkflowApprovalClient}. Môi trường chưa khai {@code
 * mission.config.workflow.base-url} thì bỏ qua (ghi nhật ký), duyệt vẫn làm được trên màn hình
 * nghiệp vụ của lõi.
 */
public class WorkflowPlanApprovalProcess implements PlanApprovalProcess {

  /** Mã mẫu luồng của DV-06-03 ({@code ApprovalTemplate.DUYET_KE_HOACH_QUY}). */
  static final String TEMPLATE = "duyet-ke-hoach-quy";

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowPlanApprovalProcess.class);

  private final ObjectProvider<WorkflowApprovalClient> client;

  public WorkflowPlanApprovalProcess(ObjectProvider<WorkflowApprovalClient> client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public Optional<String> start(TenantId tenant, Start s) {
    WorkflowApprovalClient c = client.getIfAvailable();
    if (c == null) {
      LOG.info("Chưa cấu hình workflow-service, bỏ qua khởi tạo duyệt kế hoạch {}", s.planId());
      return Optional.empty();
    }
    Map<String, Object> body = new HashMap<>();
    body.put("template", TEMPLATE);
    body.put("businessId", s.planId().toString());
    body.put("objectVersion", s.objectVersion());
    body.put("planVersionNo", s.versionNo());
    body.put("subjectUnitId", s.ownerUnitId().toString());
    body.put("flow", s.flow());
    // Đối tượng duyệt là kế hoạch của đơn vị, không phải cá nhân người đứng đầu (suy luận).
    body.put("headOfUnit", false);
    body.put("initiatorId", string(s.submittedBy()));
    body.put("periodId", string(s.periodId()));
    body.put("title", s.title());
    return Optional.ofNullable(c.start(tenant, body))
        .map(WorkflowApprovalClient.Started::processInstanceId);
  }

  @Override
  public int cancel(TenantId tenant, UUID planId, String reason) {
    WorkflowApprovalClient c = client.getIfAvailable();
    return c == null ? 0 : c.cancel(tenant, TEMPLATE, planId.toString(), reason);
  }

  private static String string(UUID id) {
    return id == null ? null : id.toString();
  }
}
