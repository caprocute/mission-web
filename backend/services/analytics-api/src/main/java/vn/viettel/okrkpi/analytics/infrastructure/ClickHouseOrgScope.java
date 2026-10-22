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
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.application.OrgScopePort;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Đọc cây tổ chức, kỳ từ kho phân tích qua giao diện HTTP của ClickHouse, bằng tài khoản Cube của
 * cell (DV-15-03) và {@code SQL_tenant_id} để row policy V009 lọc thêm theo tenant. Giá trị luôn
 * truyền bằng tham số {@code {x:Type}}, không ghép vào câu SQL.
 */
final class ClickHouseOrgScope implements OrgScopePort {

  private static final String PATHS =
      "SELECT org_path_dang, org_path_chuyen_mon FROM dim_org_scope"
          + " WHERE tenant_id = {t:UUID} AND id = {u:UUID} LIMIT 1";
  private static final String ANCESTORS =
      "SELECT toString(ancestor_id) AS ma, min(depth) AS sau FROM dim_org_closure FINAL"
          + " WHERE tenant_id = {t:UUID} AND descendant_id = {u:UUID}"
          + " GROUP BY ancestor_id ORDER BY sau, ma LIMIT {n:UInt32}";
  private static final String PREVIOUS =
      "SELECT toString(p.id) AS ma FROM cube_period AS p"
          + " CROSS JOIN (SELECT kind AS k, flow AS f, start_date AS s FROM cube_period"
          + " WHERE tenant_id = {t:UUID} AND id = {p:UUID}) AS c"
          + " WHERE p.tenant_id = {t:UUID} AND p.kind = c.k AND p.flow = c.f AND p.start_date < c.s"
          + " ORDER BY p.start_date DESC LIMIT 1";

  private final HttpClient http;
  private final String url;
  private final String database;
  private final String authorization;
  private final ObjectMapper json;
  private final Duration timeout;

  ClickHouseOrgScope(
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

  @Override
  public List<String> paths(UUID tenant, UUID unit) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : rows(tenant, PATHS, Map.of("u", unit.toString()))) {
      for (String col : List.of("org_path_dang", "org_path_chuyen_mon")) {
        String p = row.path(col).asText("");
        if (!p.isEmpty() && !out.contains(p)) {
          out.add(p);
        }
      }
    }
    return out;
  }

  @Override
  public List<UUID> unitAndAncestors(UUID tenant, UUID unit, int limit) {
    List<UUID> out = new ArrayList<>(List.of(unit));
    Map<String, String> p = Map.of("u", unit.toString(), "n", String.valueOf(limit));
    for (JsonNode row : rows(tenant, ANCESTORS, p)) {
      UUID id = UUID.fromString(row.path("ma").asText());
      if (!out.contains(id) && out.size() < limit) {
        out.add(id);
      }
    }
    return out;
  }

  @Override
  public Optional<UUID> previousPeriod(UUID tenant, UUID period) {
    return rows(tenant, PREVIOUS, Map.of("p", period.toString())).stream()
        .findFirst()
        .map(r -> UUID.fromString(r.path("ma").asText()));
  }

  private List<JsonNode> rows(UUID tenant, String sql, Map<String, String> params) {
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
