package vn.viettel.okrkpi.analytics.infrastructure;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Truy vấn kho phân tích qua giao diện HTTP của ClickHouse, bằng tài khoản Cube của cell (DV-15-03)
 * và {@code SQL_tenant_id} để row policy V009 lọc thêm theo tenant. Giá trị luôn truyền bằng tham
 * số {@code {x:Type}} ({@code param_x}), không ghép vào câu SQL; {@code {t:UUID}} là tenant.
 */
final class ClickHouseHttp {

  private final HttpClient http;
  private final String url;
  private final String database;
  private final String authorization;
  private final ObjectMapper json;
  private final Duration timeout;

  ClickHouseHttp(
      HttpClient http,
      String url,
      String database,
      String username,
      String password,
      ObjectMapper json,
      Duration timeout) {
    this.http = http;
    this.url = url.endsWith("/") ? url : url + "/";
    this.database = database;
    this.authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (username + ":" + (password == null ? "" : password))
                        .getBytes(StandardCharsets.UTF_8));
    this.json = json;
    this.timeout = timeout;
  }

  List<JsonNode> rows(UUID tenant, String sql, Map<String, String> params) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("database", database);
    q.put("SQL_tenant_id", tenant.toString());
    q.put("param_t", tenant.toString());
    params.forEach((k, v) -> q.put("param_" + k, v));
    StringBuilder uri = new StringBuilder(url).append('?');
    q.forEach(
        (k, v) ->
            uri.append(k)
                .append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8))
                .append('&'));
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(uri.substring(0, uri.length() - 1)))
            .timeout(timeout)
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(sql + " FORMAT JSONEachRow"))
            .build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, e);
    }
    if (res.statusCode() != 200) {
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, null);
    }
    List<JsonNode> out = new ArrayList<>();
    try {
      for (String line : res.body().split("\n")) {
        if (!line.isBlank()) {
          out.add(json.readTree(line));
        }
      }
    } catch (IOException e) {
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, e);
    }
    return out;
  }

  private static DependencyUnavailableException unavailable(
      DependencyUnavailableException.Reason reason, Throwable cause) {
    return new DependencyUnavailableException("clickhouse", reason, Duration.ofSeconds(5), cause);
  }
}
