package vn.viettel.okrkpi.shared.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** Cầu nối luồng duyệt mang token dịch vụ và tenant đích; phân loại lỗi để thử lại (CORE-02-08). */
class WorkflowApprovalClientTest {

  private static final String BASE = "http://workflow/internal/v1/workflow/approvals";

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final WorkflowApprovalClient client =
      new WorkflowApprovalClient(builder, "http://workflow", () -> "tok");
  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  void khoiTaoVaKetThuc() {
    server
        .expect(requestTo(BASE))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer tok"))
        .andExpect(header("X-Tenant-Id", tenant.value().toString()))
        .andRespond(
            withSuccess(
                "{\"processInstanceId\":\"p1\",\"created\":true}", MediaType.APPLICATION_JSON));
    assertThat(client.start(tenant, Map.of("template", "duyet-ke-hoach-quy")))
        .isEqualTo(new WorkflowApprovalClient.Started("p1", true));

    server.reset();
    server
        .expect(requestTo(BASE + "/cancel"))
        .andExpect(jsonPath("$.template").value("t"))
        .andExpect(jsonPath("$.businessId").value("b"))
        .andExpect(jsonPath("$.reason").value("r"))
        .andRespond(withSuccess("{\"cancelled\":1}", MediaType.APPLICATION_JSON));
    assertThat(client.cancel(tenant, "t", "b", "r")).isEqualTo(1);

    server.reset();
    server
        .expect(requestTo(BASE + "/cancel"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThat(client.cancel(tenant, "t", "b", null)).isZero();
  }

  @Test
  void phanLoaiLoi() {
    server.expect(requestTo(BASE)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThatThrownBy(() -> client.start(tenant, Map.of()))
        .isInstanceOf(WorkflowApprovalClient.Rejected.class);

    server.reset();
    server.expect(requestTo(BASE)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThatThrownBy(() -> client.start(tenant, Map.of()))
        .isInstanceOf(DependencyUnavailableException.class);

    server.reset();
    server.expect(requestTo(BASE)).andRespond(withServerError());
    assertThatThrownBy(() -> client.start(tenant, Map.of()))
        .isInstanceOf(DependencyUnavailableException.class);

    server.reset();
    server.expect(requestTo(BASE)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.start(tenant, Map.of()))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
