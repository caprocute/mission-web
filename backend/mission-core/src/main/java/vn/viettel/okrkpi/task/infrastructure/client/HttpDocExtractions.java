package vn.viettel.okrkpi.task.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec.Assignee;

/**
 * {@link DocExtractions} qua SVC-14 ai-gateway: {@code GET /api/v1/ai/extractions?sourceDocId=}
 * ({@code getDocExtraction}) và {@code POST /api/v1/ai/suggestions/{id}/decision} ({@code
 * decideAiSuggestion}), chuyển tiếp {@code Authorization} của người dùng như {@code
 * AiSuggestionClient}. Thời hạn ngắn vì danh sách văn bản đọc kết quả cho từng dòng.
 */
public class HttpDocExtractions implements DocExtractions {

  static final String EXTRACTIONS = "/api/v1/ai/extractions?sourceDocId={id}";
  static final String DECISION = "/api/v1/ai/suggestions/{id}/decision";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final RestClient http;

  public HttpDocExtractions(String baseUrl, Duration timeout) {
    this(build(baseUrl, timeout));
  }

  HttpDocExtractions(RestClient http) {
    this.http = Objects.requireNonNull(http, "http");
  }

  private static RestClient build(String baseUrl, Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 2_000));
    factory.setReadTimeout((int) timeout.toMillis());
    return RestClient.builder()
        .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
        .requestFactory(factory)
        .build();
  }

  @Override
  public Optional<Extraction> find(TenantId tenant, UUID sourceDocId, String authorization) {
    JsonNode body;
    try {
      body =
          http.get()
              .uri(EXTRACTIONS, sourceDocId)
              .accept(MediaType.APPLICATION_JSON)
              .headers(h -> headers(h, tenant, authorization))
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return Optional.empty();
      }
      throw new Unavailable("SVC-14 trả lỗi " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new Unavailable("Không gọi được SVC-14", e);
    }
    if (body == null || !body.hasNonNull("status")) {
      throw new Unavailable("SVC-14 trả kết quả trích không hợp lệ", null);
    }
    try {
      List<Suggestion> items = new ArrayList<>();
      for (JsonNode s : body.path("suggestions")) {
        items.add(suggestion(s));
      }
      return Optional.of(new Extraction(body.get("status").asText(), items));
    } catch (RuntimeException e) {
      throw new Unavailable("SVC-14 trả gợi ý không hợp lệ", e);
    }
  }

  @Override
  public void accept(
      TenantId tenant,
      UUID suggestionId,
      boolean edited,
      List<String> editedFields,
      String targetRef,
      String authorization,
      String idempotencyKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", edited ? "SUA" : "CHAP_NHAN");
    if (edited) {
      body.put("editedFields", editedFields);
    }
    body.put("targetRef", targetRef);
    try {
      http.post()
          .uri(DECISION, suggestionId)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .headers(
              h -> {
                headers(h, tenant, authorization);
                h.set(IDEMPOTENCY_KEY, idempotencyKey);
              })
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 409) {
        return;
      }
      throw new Unavailable("SVC-14 trả lỗi " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new Unavailable("Không gọi được SVC-14", e);
    }
  }

  private static void headers(HttpHeaders h, TenantId tenant, String authorization) {
    h.set(TENANT_HEADER, tenant.value().toString());
    if (authorization != null) {
      h.set(HttpHeaders.AUTHORIZATION, authorization);
    }
  }

  static Suggestion suggestion(JsonNode s) {
    List<String> edited = new ArrayList<>();
    s.path("editedFields").forEach(n -> edited.add(n.asText()));
    return new Suggestion(
        UUID.fromString(s.get("id").asText()),
        s.get("paragraphNo").asInt(),
        s.hasNonNull("confidence") ? s.get("confidence").decimalValue() : BigDecimal.ZERO,
        s.path("decision").asText("CHO_XAC_NHAN"),
        fields(s.path("fields")),
        fields(s.path("aiFields")),
        edited,
        text(s, "reason"));
  }

  static Fields fields(JsonNode f) {
    if (f == null || f.isMissingNode() || f.isNull()) {
      return null;
    }
    List<Assignee> units = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (JsonNode c : f.path("coUnits")) {
      if (c.hasNonNull("id")) {
        units.add(
            new Assignee(c.path("kind").asText("UNIT"), UUID.fromString(c.get("id").asText())));
        names.add(c.path("name").asText(""));
      }
    }
    return new Fields(
        text(f, "title"),
        uuid(f, "leadUnitId"),
        text(f, "leadUnitName"),
        units,
        names,
        date(f, "dueDate"),
        text(f, "productName"),
        uuid(f, "catalogItemId"),
        text(f, "catalogItemCode"),
        uuid(f, "keyResultId"),
        text(f, "keyResultCode"));
  }

  private static String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asText() : null;
  }

  private static UUID uuid(JsonNode n, String field) {
    String v = text(n, field);
    return v == null || v.isBlank() ? null : UUID.fromString(v);
  }

  private static LocalDate date(JsonNode n, String field) {
    String v = text(n, field);
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
