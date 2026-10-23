package vn.viettel.okrkpi.contract.pact;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Xác minh phía cung cấp (QA-02) trong cùng tiến trình: phát lại yêu cầu của từng tương tác qua
 * {@link MockMvc} vào ngữ cảnh mission-core thật (PostgreSQL Testcontainers) rồi so phản hồi như
 * {@code PactVerifier} của integration-gateway: mã trạng thái, Content-Type theo phần kiểu, thân
 * theo luật so khớp và cho phép trường thừa.
 *
 * <p>Thao tác ghi không có {@code Idempotency-Key} trong hợp đồng được thêm một khoá mới, vì ở web
 * khoá do interceptor của {@code @vt/api-client} đặt cho mọi yêu cầu ghi (giá trị ngẫu nhiên nên
 * không ghi vào pact). Mọi yêu cầu mang {@code principal} là người gọi (khoá chống trùng gắn theo
 * người gọi, như các kiểm thử tích hợp API của mission-core).
 */
public final class MockMvcPactVerifier {

  /** Dựng trạng thái bên cung cấp rồi chạy yêu cầu trong ngữ cảnh mà trạng thái đó trả về. */
  public interface States {
    Callable<MvcResult> setup(Pact.State state, Callable<MvcResult> request) throws Exception;
  }

  private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final MockMvc mvc;
  private final String principal;
  private final States states;

  public MockMvcPactVerifier(MockMvc mvc, String principal, States states) {
    this.mvc = mvc;
    this.principal = principal;
    this.states = states;
  }

  /** Lỗi của mọi tương tác, mỗi dòng mở đầu bằng mô tả tương tác; rỗng là đạt. */
  public List<String> failures(Pact pact) {
    List<String> out = new ArrayList<>();
    for (Pact.Interaction i : pact.interactions()) {
      verify(i).forEach(e -> out.add(i.description() + ": " + e));
    }
    return out;
  }

  public List<String> verify(Pact.Interaction i) {
    List<String> errors = new ArrayList<>();
    try {
      Callable<MvcResult> call = () -> mvc.perform(build(i)).andReturn();
      for (Pact.State s : i.states()) {
        call = states.setup(s, call);
      }
      MvcResult res = call.call();
      JsonNode expected = i.response();
      int status = expected.path("status").asInt(200);
      int actualStatus = res.getResponse().getStatus();
      if (actualStatus != status) {
        errors.add("mã trạng thái " + actualStatus + ", hợp đồng cần " + status);
        return errors;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = expected.path("headers").fields();
          it.hasNext(); ) {
        Map.Entry<String, JsonNode> h = it.next();
        String actual = res.getResponse().getHeader(h.getKey());
        if (actual == null) {
          errors.add("thiếu header " + h.getKey());
        } else if ("content-type".equalsIgnoreCase(h.getKey())) {
          if (!mediaType(actual).equals(mediaType(h.getValue().asText()))) {
            errors.add("Content-Type " + mediaType(actual) + " khác hợp đồng");
          }
        } else if (!PactMatcher.value(
            h.getKey(), h.getValue().asText(), actual, i.responseRules("header"))) {
          errors.add("header " + h.getKey() + " khác hợp đồng");
        }
      }
      JsonNode body = expected.path("body");
      if (body.isContainerNode()) {
        JsonNode actual;
        try {
          actual = Pact.JSON.readTree(res.getResponse().getContentAsByteArray());
        } catch (IOException e) {
          errors.add("thân phản hồi không phải JSON");
          return errors;
        }
        errors.addAll(PactMatcher.body(body, actual, i.responseRules("body"), true));
      }
    } catch (Exception e) {
      errors.add("không chạy được: " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
    return errors;
  }

  private MockHttpServletRequestBuilder build(Pact.Interaction i) {
    MockHttpServletRequestBuilder b =
        MockMvcRequestBuilders.request(HttpMethod.valueOf(i.method()), i.path())
            .principal(() -> principal);
    i.request()
        .path("query")
        .fields()
        .forEachRemaining(q -> q.getValue().forEach(v -> b.queryParam(q.getKey(), v.asText())));
    boolean hasKey = false;
    for (Iterator<Map.Entry<String, JsonNode>> it = i.request().path("headers").fields();
        it.hasNext(); ) {
      Map.Entry<String, JsonNode> h = it.next();
      hasKey |= "idempotency-key".equalsIgnoreCase(h.getKey());
      if ("content-type".equalsIgnoreCase(h.getKey())) {
        b.contentType(h.getValue().asText());
      } else {
        b.header(h.getKey(), h.getValue().asText());
      }
    }
    if (WRITES.contains(i.method()) && !hasKey) {
      b.header("Idempotency-Key", UUID.randomUUID().toString());
    }
    JsonNode body = i.request().path("body");
    if (!body.isMissingNode()) {
      b.content(
          (body.isTextual() ? body.asText() : body.toString()).getBytes(StandardCharsets.UTF_8));
    }
    return b;
  }

  private static String mediaType(String contentType) {
    return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
  }
}
