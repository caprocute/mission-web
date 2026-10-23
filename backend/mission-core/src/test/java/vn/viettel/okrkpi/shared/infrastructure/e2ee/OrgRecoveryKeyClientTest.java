package vn.viettel.okrkpi.shared.infrastructure.e2ee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** Client {@code getRecoveryKey} của SVC-04: header uỷ quyền tenant, 404 là chưa khai báo. */
class OrgRecoveryKeyClientTest {

  private static final String URL = "http://org/api/v1/org/e2ee/recovery-key";
  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  void docKhoaHienHanhKemTenantDich() {
    RestClient.Builder b = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
    UUID id = UUID.randomUUID();
    String fp = "ab".repeat(32);
    server
        .expect(requestTo(URL))
        .andExpect(header("Authorization", "Bearer t"))
        .andExpect(header(OrgRecoveryKeyClient.TENANT_HEADER, tenant.value().toString()))
        .andRespond(
            withSuccess(
                "{\"id\":\"" + id + "\",\"fingerprint\":\"" + fp + "\",\"status\":\"HIEU_LUC\"}",
                MediaType.APPLICATION_JSON));
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
    server
        .expect(requestTo(URL))
        .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
    OrgRecoveryKeyClient client = new OrgRecoveryKeyClient(b, "http://org", () -> "t");

    assertThat(client.current(tenant)).contains(new OrgRecoveryKeyClient.Current(id, fp));
    assertThat(client.current(tenant)).isEmpty();
    assertThatThrownBy(() -> client.current(tenant))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThatThrownBy(() -> client.current(tenant))
        .isInstanceOf(DependencyUnavailableException.class);
    server.verify();
  }
}
