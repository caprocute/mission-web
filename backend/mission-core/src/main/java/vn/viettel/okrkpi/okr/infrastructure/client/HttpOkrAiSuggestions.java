package vn.viettel.okrkpi.okr.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions;
import vn.viettel.okrkpi.shared.infrastructure.ai.AiSuggestionClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link OkrAiSuggestions} qua {@link AiSuggestionClient} (CORE-01-13). {@code outputText} của gợi
 * ý con do ai-gateway ghi dạng JSON ({@code {kind, objective...}}); văn bản thường được đặt vào
 * khoá {@code text}. Nhật ký chỉ ghi mã gợi ý và loại lỗi, không ghi nội dung.
 */
public class HttpOkrAiSuggestions implements OkrAiSuggestions {

  private static final Logger LOG = LoggerFactory.getLogger(HttpOkrAiSuggestions.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final AiSuggestionClient client;
  private final ObjectMapper json;

  public HttpOkrAiSuggestions(AiSuggestionClient client, ObjectMapper json) {
    this.client = Objects.requireNonNull(client, "client");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<Suggestion> find(
      TenantId tenant, UUID user, UUID suggestionId, String authorization) {
    Optional<JsonNode> body;
    try {
      body = client.find(tenant, suggestionId, authorization);
    } catch (IllegalStateException e) {
      LOG.warn(
          "Không đọc được gợi ý AI suggestionId={} err={}",
          suggestionId,
          e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getName());
      throw new Unavailable("SVC-14 không trả lời", e);
    }
    return body.map(b -> suggestion(suggestionId, b));
  }

  Suggestion suggestion(UUID id, JsonNode b) {
    String decision = b.path("decision").isTextual() ? b.path("decision").asText() : null;
    String kind = b.path("kind").isTextual() ? b.path("kind").asText() : null;
    return new Suggestion(id, kind, decision, output(b.path("outputText")));
  }

  private Map<String, Object> output(JsonNode text) {
    if (!text.isTextual()) {
      return Map.of();
    }
    String raw = text.asText();
    try {
      JsonNode parsed = json.readTree(raw);
      if (parsed != null && parsed.isObject()) {
        return json.convertValue(parsed, MAP);
      }
    } catch (JsonProcessingException e) {
      // văn bản thường
    }
    return Map.of("text", raw);
  }
}
