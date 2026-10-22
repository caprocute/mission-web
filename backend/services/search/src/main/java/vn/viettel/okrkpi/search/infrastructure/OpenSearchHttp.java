package vn.viettel.okrkpi.search.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import vn.viettel.okrkpi.search.application.TransientIndexException;

/** Lời gọi REST tới OpenSearch bằng HttpClient của JDK (không thêm thư viện client). */
public final class OpenSearchHttp {

  public record Response(int status, JsonNode body) {
    public boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  private final HttpClient http;
  private final URI base;
  private final String authorization;
  private final Duration timeout;
  private final ObjectMapper json;

  public OpenSearchHttp(
      String url, String username, String password, Duration timeout, ObjectMapper json) {
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.base = URI.create(url.endsWith("/") ? url : url + "/");
    this.authorization =
        username == null || username.isBlank()
            ? null
            : "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (username + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
    this.timeout = timeout;
    this.json = json;
  }

  public Response send(String method, String path, Object body) {
    try {
      String payload =
          body == null ? null : body instanceof String s ? s : json.writeValueAsString(body);
      return raw(method, path, payload, "application/json");
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Không tuần tự được thân yêu cầu OpenSearch", e);
    }
  }

  public Response ndjson(String path, String body) {
    return raw("POST", path, body, "application/x-ndjson");
  }

  private Response raw(String method, String path, String body, String contentType) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(base.resolve(path.startsWith("/") ? path.substring(1) : path))
            .timeout(timeout)
            .header("Accept", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (body != null) {
      b.header("Content-Type", contentType);
    }
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    try {
      HttpResponse<String> r =
          http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode node =
          r.body() == null || r.body().isBlank()
              ? MissingNode.getInstance()
              : json.readTree(r.body());
      return new Response(r.statusCode(), node);
    } catch (IOException e) {
      throw new TransientIndexException(
          "Không gọi được OpenSearch: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransientIndexException("Bị ngắt khi gọi OpenSearch", e);
    }
  }
}
