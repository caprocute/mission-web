package vn.viettel.okrkpi.integ.infrastructure.hrm;

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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Máy khách HTTP chung của bộ kết nối REST (HRM, HIS...): địa chỉ gốc từ {@code
 * integ.connector.endpoint}, mã thông báo từ {@code secret_path}; mã trả khác 2xx là lỗi vào ra.
 * Nội dung trả về không ghi nhật ký vì có thể chứa dữ liệu C2.
 */
class HrmHttp {

  private final HttpClient http;
  private final ObjectMapper json;
  private final ConnectorSecrets secrets;

  HrmHttp(HttpClient http, ObjectMapper json, ConnectorSecrets secrets) {
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
  }

  ObjectMapper json() {
    return json;
  }

  byte[] get(Connector connector, String path, Map<String, String> query, Duration timeout)
      throws IOException {
    String endpoint = connector.endpoint();
    if (endpoint == null || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
      throw new IOException("Địa chỉ kết nối " + connector.code() + " không hợp lệ");
    }
    String qs =
        query.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create(base + path + (qs.isEmpty() ? "" : "?" + qs)))
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET();
    if (connector.secretPath() != null) {
      secrets
          .token(connector.secretPath())
          .ifPresent(t -> req.header("Authorization", "Bearer " + t));
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi " + connector.code(), e);
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Kết nối " + connector.code() + " trả mã " + res.statusCode());
    }
    return res.body();
  }

  JsonNode getJson(Connector connector, String path, Map<String, String> query, Duration timeout)
      throws IOException {
    return json.readTree(get(connector, path, query, timeout));
  }
}
