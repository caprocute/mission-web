package vn.viettel.okrkpi.task.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowApprovalClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalProcess;

/** Thân yêu cầu khởi tạo {@code duyet-ke-hoach-quy}; chưa cấu hình SVC-06 thì bỏ qua. */
class WorkflowPlanApprovalProcessTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<WorkflowApprovalClient> provider = mock(ObjectProvider.class);

  private final WorkflowApprovalClient client = mock(WorkflowApprovalClient.class);
  private final WorkflowPlanApprovalProcess process = new WorkflowPlanApprovalProcess(provider);
  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  @SuppressWarnings("unchecked")
  void guiDungMauVaKhoaNghiepVu() {
    UUID plan = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    when(provider.getIfAvailable()).thenReturn(client);
    when(client.start(eq(tenant), anyMap()))
        .thenReturn(new WorkflowApprovalClient.Started("p9", false));
    assertThat(
            process.start(
                tenant,
                new PlanApprovalProcess.Start(plan, 3, 11, unit, "DANG", null, null, "Duyệt")))
        .contains("p9");
    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(client).start(eq(tenant), body.capture());
    assertThat(body.getValue())
        .containsEntry("template", "duyet-ke-hoach-quy")
        .containsEntry("businessId", plan.toString())
        .containsEntry("objectVersion", 11L)
        .containsEntry("planVersionNo", 3)
        .containsEntry("subjectUnitId", unit.toString())
        .containsEntry("flow", "DANG")
        .containsEntry("headOfUnit", false)
        .containsEntry("initiatorId", null);

    when(client.cancel(tenant, "duyet-ke-hoach-quy", plan.toString(), "x")).thenReturn(1);
    assertThat(process.cancel(tenant, plan, "x")).isEqualTo(1);
  }

  @Test
  void boQuaKhiChuaCauHinh() {
    assertThat(
            process.start(
                tenant,
                new PlanApprovalProcess.Start(
                    UUID.randomUUID(), 1, 1, UUID.randomUUID(), "DANG", null, null, "t")))
        .isEmpty();
    assertThat(process.cancel(tenant, UUID.randomUUID(), "x")).isZero();
  }
}
