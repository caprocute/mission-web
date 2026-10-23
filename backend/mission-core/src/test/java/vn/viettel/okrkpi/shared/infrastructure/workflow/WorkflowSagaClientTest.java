package vn.viettel.okrkpi.shared.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** Lời gọi saga chốt kỳ mang token dịch vụ, tenant đích và khoá idempotency (CORE-08-05). */
class WorkflowSagaClientTest {

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final WorkflowSagaClient client =
      new WorkflowSagaClient(builder, "http://workflow", () -> "tok");
  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  void sendsTenantHeaderAndMapsConflict() {
    UUID job = UUID.randomUUID();
    server
        .expect(requestTo("http://workflow/api/v1/workflow/sagas/period-close"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer tok"))
        .andExpect(header("X-Tenant-Id", tenant.value().toString()))
        .andExpect(header("Idempotency-Key", "CK-2026Q4"))
        .andRespond(withSuccess("{\"id\":\"wf-" + job + "\"}", MediaType.APPLICATION_JSON));
    assertThat(
            client.startPeriodClose(
                tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CK-2026Q4"))
        .isEqualTo(job);

    server.reset();
    server
        .expect(requestTo("http://workflow/api/v1/workflow/sagas/period-close"))
        .andRespond(withStatus(HttpStatus.CONFLICT));
    assertThatThrownBy(
            () ->
                client.startPeriodClose(
                    tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "k"))
        .isInstanceOf(WorkflowSagaClient.SagaExists.class);

    server.reset();
    server
        .expect(requestTo("http://workflow/api/v1/workflow/sagas/period-close"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(
            () ->
                client.startPeriodClose(
                    tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "k"))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
