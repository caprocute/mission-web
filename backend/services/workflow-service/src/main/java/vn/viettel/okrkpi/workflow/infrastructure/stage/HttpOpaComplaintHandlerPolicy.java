package vn.viettel.okrkpi.workflow.infrastructure.stage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.workflow.application.port.ComplaintHandlerPolicy;

/**
 * Hỏi OPA cùng pod (ADR-21) quyết định {@code data.okrkpi.complaint.decision}. Lỗi kết nối ném
 * ngoại lệ để worker chọn người thử lại (đóng an toàn: không giao việc khi chưa có quyết định).
 */
@Component
public class HttpOpaComplaintHandlerPolicy implements ComplaintHandlerPolicy {

  static final String PATH = "/v1/data/okrkpi/complaint/decision";

  private final RestClient http;

  public HttpOpaComplaintHandlerPolicy(
      RestClient.Builder builder,
      @Value("${okrkpi.opa.base-url:http://127.0.0.1:8181}") String baseUrl) {
    this.http = builder.clone().baseUrl(baseUrl).build();
  }

  @Override
  public Optional<Decision> decide(
      List<UUID> units, Map<UUID, List<UUID>> appraisers, Map<String, List<UUID>> participants) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("units", units.stream().map(UUID::toString).toList());
    Map<String, List<String>> holders = new LinkedHashMap<>();
    appraisers.forEach(
        (u, p) -> holders.put(u.toString(), p.stream().map(UUID::toString).toList()));
    input.put("appraisers", holders);
    Map<String, List<String>> groups = new LinkedHashMap<>();
    participants.forEach((g, p) -> groups.put(g, p.stream().map(UUID::toString).toList()));
    input.put("participants", groups);
    JsonNode body =
        http.post()
            .uri(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("input", input))
            .retrieve()
            .body(JsonNode.class);
    return parse(body);
  }

  static Optional<Decision> parse(JsonNode body) {
    JsonNode result = body == null ? null : body.get("result");
    if (result == null || result.path("unit").isNull() || result.path("unit").isMissingNode()) {
      return Optional.empty();
    }
    List<UUID> candidates = new ArrayList<>();
    result.path("candidates").forEach(c -> candidates.add(UUID.fromString(c.asText())));
    return Optional.of(
        new Decision(
            UUID.fromString(result.get("unit").asText()),
            candidates,
            result.path("escalated").asBoolean(false)));
  }
}
