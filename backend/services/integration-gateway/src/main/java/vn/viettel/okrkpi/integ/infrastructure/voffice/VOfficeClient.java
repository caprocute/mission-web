package vn.viettel.okrkpi.integ.infrastructure.voffice;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Máy khách HTTP của API vOffice (TL-01 "Các bộ kết nối": REST OpenAPI, kéo có con trỏ). Địa chỉ
 * gốc lấy từ {@code integ.connector.endpoint}, mã thông báo từ {@code secret_path}; đổi sang hệ
 * thật chỉ cần đổi bản ghi kết nối. Mã trả khác 2xx là lỗi vào ra để circuit breaker đếm; nội dung
 * trả về không ghi nhật ký.
 */
class VOfficeClient {

  static final String DIRECTIVES = "/api/v2/directives";
  static final String DOCUMENTS = "/api/v2/documents";
  static final String DOCUMENT_DETAIL = "/api/v2/documents/detail";
  static final String DOCUMENT_CONTENT = "/api/v2/documents/content";

  private final HttpClient http;
  private final ObjectMapper json;
  private final ConnectorSecrets secrets;
  private final Duration timeout;

  VOfficeClient(HttpClient http, ObjectMapper json, ConnectorSecrets secrets, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  ObjectMapper json() {
    return json;
  }

  JsonNode get(Connector connector, String path, Map<String, String> query) throws IOException {
    HttpResponse<byte[]> res = send(connector, path, query, "application/json");
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Kết nối " + connector.code() + " trả mã " + res.statusCode());
    }
    return json.readTree(res.body());
  }

  /** Như {@link #get} nhưng 404 là rỗng (văn bản không có hoặc không thuộc đơn vị). */
  Optional<JsonNode> find(Connector connector, String path, Map<String, String> query)
      throws IOException {
    HttpResponse<byte[]> res = send(connector, path, query, "application/json");
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Kết nối " + connector.code() + " trả mã " + res.statusCode());
    }
    return Optional.of(json.readTree(res.body()));
  }

  /** Nội dung nhị phân (tệp gốc), tối đa {@code maxBytes}; 404 là rỗng. */
  Optional<byte[]> download(
      Connector connector, String path, Map<String, String> query, long maxBytes)
      throws IOException {
    HttpResponse<byte[]> res = send(connector, path, query, "*/*");
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Kết nối " + connector.code() + " trả mã " + res.statusCode());
    }
    if (res.body().length > maxBytes) {
      throw new IOException("Tệp gốc từ " + connector.code() + " vượt giới hạn kích thước");
    }
    return Optional.of(res.body());
  }

  private HttpResponse<byte[]> send(
      Connector connector, String path, Map<String, String> query, String accept)
      throws IOException {
    String endpoint = connector.endpoint();
    if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
      throw new IOException("Địa chỉ kết nối " + connector.code() + " không hợp lệ");
    }
    Map<String, String> params = new LinkedHashMap<>();
    query.forEach(
        (k, v) -> {
          if (v != null && !v.isEmpty()) {
            params.put(k, v);
          }
        });
    String qs =
        params.entrySet().stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    URI uri = URI.create(trimSlash(endpoint) + path + (qs.isEmpty() ? "" : "?" + qs));
    HttpRequest.Builder req =
        HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", accept).GET();
    if (connector.secretPath() != null) {
      secrets
          .token(connector.secretPath())
          .ifPresent(t -> req.header("Authorization", "Bearer " + t));
    }
    try {
      return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi " + connector.code(), e);
    }
  }

  private static String trimSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
