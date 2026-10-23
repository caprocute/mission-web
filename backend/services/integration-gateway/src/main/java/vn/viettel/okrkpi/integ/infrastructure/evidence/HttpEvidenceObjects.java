package vn.viettel.okrkpi.integ.infrastructure.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.port.EvidenceObjects;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link EvidenceObjects} qua cổng nội bộ {@code POST /internal/v1/ingested-objects} của SVC-08
 * evidence-service: thân là nội dung tệp, xác thực dịch vụ bằng mTLS của lưới (như cổng OCR của
 * DV-08-05). Mã trả khác 2xx là lỗi vào ra để bên gọi thử lại.
 */
public class HttpEvidenceObjects implements EvidenceObjects {

  static final String PATH = "/internal/v1/ingested-objects";

  private final HttpClient http;
  private final ObjectMapper json;
  private final String baseUrl;
  private final Duration timeout;

  public HttpEvidenceObjects(HttpClient http, ObjectMapper json, String baseUrl, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.baseUrl = trimSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public Stored ingest(
      TenantId tenant,
      String idempotencyKey,
      UUID ownerRefId,
      UUID requestedBy,
      String fileName,
      String mediaType,
      byte[] content)
      throws IOException {
    String query =
        "tenantId="
            + tenant.value()
            + "&ownerRefId="
            + ownerRefId
            + "&requestedBy="
            + requestedBy
            + (fileName == null
                ? ""
                : "&fileName=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUrl + PATH + "?" + query))
            .timeout(timeout)
            .header("Content-Type", mediaType)
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(content))
            .build();
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi evidence-service", e);
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("evidence-service trả mã " + res.statusCode());
    }
    JsonNode n = json.readTree(res.body());
    try {
      return new Stored(
          UUID.fromString(n.path("objectId").asText()),
          n.path("objectVersion").asInt(),
          HexFormat.of().parseHex(n.path("sha256").asText().toLowerCase(Locale.ROOT)),
          n.path("sizeBytes").asLong(),
          n.path("mediaType").asText());
    } catch (IllegalArgumentException e) {
      throw new IOException("evidence-service trả thân không hợp lệ", e);
    }
  }

  private static String trimSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
