package vn.viettel.okrkpi.shared.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class SearchClientTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());

  @Test
  void forwardsAuthorizationAndParsesIds() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://search");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    UUID id = UUID.randomUUID();
    UUID period = UUID.randomUUID();
    server
        .expect(queryParam("type", "TASK"))
        .andExpect(queryParam("period", period.toString()))
        .andExpect(header("Authorization", "Bearer t"))
        .andExpect(header(SearchClient.TENANT_HEADER, T.value().toString()))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"id\":\"" + id + "\",\"score\":3.5},{\"id\":\"x\"}]}",
                MediaType.APPLICATION_JSON));
    SearchClient client = new SearchClient(builder.build());
    assertThat(client.search(T, "Báo cáo", "TASK", period, 50, "Bearer t"))
        .containsExactly(new SearchClient.Hit(id, 3.5));
    server.verify();
  }

  @Test
  void serverErrorIsIllegalState() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://search");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(queryParam("q", "ab")).andRespond(withServerError());
    SearchClient client = new SearchClient(builder.build());
    assertThatThrownBy(() -> client.search(T, "ab", "TASK", null, 10, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new SearchClient("http://localhost:1", java.time.Duration.ofMillis(100)))
        .isNotNull();
  }
}
