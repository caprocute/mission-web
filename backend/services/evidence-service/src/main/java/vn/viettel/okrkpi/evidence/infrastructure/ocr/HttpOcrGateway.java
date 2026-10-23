package vn.viettel.okrkpi.evidence.infrastructure.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import vn.viettel.okrkpi.evidence.application.OcrGateway;

/**
 * Gọi {@code POST /api/v1/ai/ocr} của ai-gateway ({@code ocrEvidenceObject}) bằng token dịch vụ,
 * kèm {@code Idempotency-Key}. 422 là ảnh không đủ nét, 4xx khác là từ chối vĩnh viễn, 5xx và 429
 * là lỗi tạm thời để worker thử lại. Văn bản trả về không ghi nhật ký. Chưa hỗ trợ nhánh 202 bất
 * đồng bộ (ai-gateway hiện luôn trả 200).
 */
public class HttpOcrGateway implements OcrGateway {

  static final String PATH = "/api/v1/ai/ocr";

  private final HttpClient http;
  private final ObjectMapper json;
  private final URI endpoint;
  private final ServiceTokenSource tokens;
  private final Duration timeout;

  HttpOcrGateway(
      HttpClient http,
      ObjectMapper json,
      String baseUrl,
      ServiceTokenSource tokens,
      Duration timeout) {
    this.http = http;
    this.json = json;
    this.endpoint =
        URI.create(
            (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + PATH);
    this.tokens = tokens;
    this.timeout = timeout;
  }

  /** Dựng cổng từ cấu hình; {@code tokenUrl} trống thì không gửi token (bản giả lập). */
  public static HttpOcrGateway create(
      ObjectMapper json,
      String baseUrl,
      String tokenUrl,
      String clientId,
      String clientSecret,
      Duration timeout,
      Clock clock) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new HttpOcrGateway(
        http,
        json,
        baseUrl,
        new ServiceTokenSource(http, json, tokenUrl, clientId, clientSecret, timeout, clock),
        timeout);
  }

  @Override
  public Result read(Request request, String idempotencyKey) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("objectKey", request.objectKey());
    body.put("sha256", request.sha256Hex());
    body.put("mimeType", request.mimeType());
    body.put("classification", request.classification());
    body.put("languageHint", "vi");
    HttpRequest.Builder req =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
    tokens.bearer().ifPresent(t -> req.header("Authorization", "Bearer " + t));
    HttpResponse<byte[]> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi ai-gateway", e);
    }
    int status = res.statusCode();
    if (status == 422) {
      throw new UnreadableException("ai-gateway không đọc được chữ");
    }
    if (status == 429 || status >= 500) {
      throw new IOException("ai-gateway trả mã " + status);
    }
    if (status != 200) {
      throw new RefusedException("ai-gateway trả mã " + status);
    }
    JsonNode n = json.readTree(res.body());
    Instant processed;
    try {
      processed = Instant.parse(n.path("processedAt").asText(""));
    } catch (DateTimeParseException e) {
      processed = null;
    }
    return new Result(
        n.path("modelId").asText("khong-ro"),
        n.path("fullText").asText(""),
        n.path("pages").size(),
        processed);
  }
}
