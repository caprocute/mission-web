package vn.viettel.okrkpi.contract.pact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.policy.infrastructure.client.WorkflowBindingClient;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowApprovalClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalProcess;
import vn.viettel.okrkpi.task.infrastructure.client.WorkflowPlanApprovalProcess;

/**
 * QA-02 · bên tiêu dùng service với service: mission-core gọi API nội bộ của workflow-service
 * (SVC-06) theo {@code src/test/resources/pacts/mission-core-workflow-service.json}, gồm cầu nối
 * luồng duyệt {@code /internal/v1/workflow/approvals} (CORE-02-08, gọi qua {@link
 * WorkflowPlanApprovalProcess} như lúc trình kế hoạch quý) và tham số luồng {@code
 * /internal/v1/workflow/bindings} (CORE-05-11). workflow-service xác minh cùng tệp ở {@code
 * MissionCorePactIT}.
 */
class WorkflowServicePactTest {

  static final Path PACT = Path.of("src/test/resources/pacts/mission-core-workflow-service.json");

  @Test
  void trinhKeHoachQuyKhoiTaoLuongDuyet() throws Exception {
    Pact.Interaction i = Pact.load(PACT).interaction("khởi tạo luồng duyệt kế hoạch quý");
    try (PactMockProvider wf = new PactMockProvider(i)) {
      var body = i.request().path("body");
      var started =
          plans(wf)
              .start(
                  tenant(i),
                  new PlanApprovalProcess.Start(
                      UUID.randomUUID(),
                      1,
                      2,
                      UUID.fromString(body.path("subjectUnitId").asText()),
                      "DANG",
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      "Kế hoạch công tác Quý I/2027"));
      assertThat(wf.mismatches()).isEmpty();
      assertThat(started).contains(i.response().at("/body/processInstanceId").asText());
    }
  }

  @Test
  void rutKeHoachKetThucLuongDuyet() throws Exception {
    Pact.Interaction i =
        Pact.load(PACT).interaction("kết thúc luồng duyệt kế hoạch còn chờ người duyệt");
    try (PactMockProvider wf = new PactMockProvider(i)) {
      int cancelled =
          plans(wf)
              .cancel(
                  tenant(i),
                  UUID.fromString(i.request().at("/body/businessId").asText()),
                  "Người trình rút lại kế hoạch");
      assertThat(wf.mismatches()).isEmpty();
      assertThat(cancelled).isEqualTo(1);
    }
  }

  @Test
  void thamSoLuongChuaLuuThiRong() throws Exception {
    Pact.Interaction i = Pact.load(PACT).interaction("tham số luồng chưa lưu cho loại quy trình");
    try (PactMockProvider wf = new PactMockProvider(i)) {
      ResilientClientFactory factory =
          new ResilientClientFactory(
              new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
              CircuitBreakerRegistry.ofDefaults(),
              RetryRegistry.ofDefaults(),
              TimeLimiterRegistry.of(
                  TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
              BulkheadRegistry.ofDefaults());
      try {
        String token = wf.token();
        WorkflowBindingClient client =
            new WorkflowBindingClient(
                factory.create(WorkflowBindingClient.INSTANCE, wf.baseUrl()), () -> token);
        UUID version = UUID.fromString(i.request().at("/query/policyPackVersionId/0").asText());
        assertThat(client.find(tenant(i), version, "DANH_GIA")).isEmpty();
        assertThat(wf.mismatches()).isEmpty();
      } finally {
        factory.destroy();
      }
    }
  }

  private static WorkflowPlanApprovalProcess plans(PactMockProvider wf) {
    String token = wf.token();
    WorkflowApprovalClient client =
        new WorkflowApprovalClient(RestClient.builder(), wf.baseUrl(), () -> token);
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("workflowApprovalClient", client);
    return new WorkflowPlanApprovalProcess(beans.getBeanProvider(WorkflowApprovalClient.class));
  }

  private static TenantId tenant(Pact.Interaction i) {
    return TenantId.of(UUID.fromString(i.request().at("/headers/X-Tenant-Id").asText()));
  }
}
