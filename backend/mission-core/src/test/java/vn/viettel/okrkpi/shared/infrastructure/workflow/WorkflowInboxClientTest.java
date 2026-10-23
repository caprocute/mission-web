package vn.viettel.okrkpi.shared.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Hộp việc đọc bằng token người dùng, gom các trang; lỗi trả rỗng (CORE-02-08). */
class WorkflowInboxClientTest {

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final WorkflowInboxClient client = new WorkflowInboxClient(builder, "http://workflow");

  @Test
  void gomCacTrang() {
    server
        .expect(requestTo(Matchers.startsWith("http://workflow/api/v1/workflow/inbox")))
        .andExpect(header("Authorization", "Bearer u"))
        .andExpect(queryParam("businessType", "PLAN"))
        .andExpect(queryParam("assignee", "me"))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"businessId\":\"a\"},{\"x\":1}],\"nextCursor\":\"c2\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(Matchers.containsString("cursor=c2")))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"businessId\":\"b\"}],\"nextCursor\":null}",
                MediaType.APPLICATION_JSON));
    assertThat(client.businessIds("u", "PLAN")).contains(List.of("a", "b"));
    server.verify();
  }

  @Test
  void loiTraRong() {
    server
        .expect(requestTo(Matchers.startsWith("http://workflow/api/v1/workflow/inbox")))
        .andRespond(withServerError());
    assertThat(client.businessIds("u", "PLAN")).isEmpty();

    server.reset();
    server
        .expect(requestTo(Matchers.startsWith("http://workflow/api/v1/workflow/inbox")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    assertThat(client.businessIds("u", "PLAN")).isEmpty();
  }
}
