package vn.viettel.okrkpi.shared.error.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import vn.viettel.okrkpi.shared.error.ProblemSchema;

/**
 * Lỗi xảy ra ngoài controller (bộ lọc servlet) đi qua trang lỗi {@code /error} của container: vẫn
 * trả Problem Details thay cho JSON lỗi mặc định của Spring Boot.
 */
@SpringBootTest(
    classes = TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProblemErrorControllerTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;

  @Test
  void sendErrorTuBoLocTraProblem401() throws Exception {
    ResponseEntity<String> r = rest.getForEntity("/filtered/x", String.class);

    JsonNode p = check(r, 401);
    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/unauthorized");
    assertThat(p.get("instance").asText()).isEqualTo("/filtered/x");
    assertThat(r.getBody()).doesNotContain("Bearer");
  }

  @Test
  void ngoaiLeTrongBoLocTraProblem500KhongLoSql() throws Exception {
    ResponseEntity<String> r = rest.getForEntity("/filter-boom/x", String.class);

    JsonNode p = check(r, 500);
    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/internal-error");
    assertThat(r.getBody()).doesNotContainIgnoringCase("select").doesNotContain("Exception");
  }

  @Test
  void loiTrongControllerQuaServerThatVanLaProblem() throws Exception {
    ResponseEntity<String> r = rest.getForEntity("/probe/business", String.class);

    JsonNode p = check(r, 409);
    assertThat(p.get("code").asText()).isEqualTo("TASK-409-PERIOD-LOCKED");
  }

  private JsonNode check(ResponseEntity<String> r, int status) throws Exception {
    assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(status);
    assertThat(r.getHeaders().getContentType())
        .matches(t -> t.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON), r.getBody());
    JsonNode node = json.readTree(r.getBody());
    assertThat(ProblemSchema.fromCommonYaml().validate(node, "ProblemDetails"))
        .as(r.getBody())
        .isEmpty();
    assertThat(r.getBody()).doesNotContain("java.").doesNotContain("trace\"");
    return node;
  }
}
