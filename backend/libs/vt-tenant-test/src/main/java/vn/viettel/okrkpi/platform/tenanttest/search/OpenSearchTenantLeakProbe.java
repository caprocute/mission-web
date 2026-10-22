package vn.viettel.okrkpi.platform.tenanttest.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * NEN-035, ADR-15, TL-03 "Đánh chỉ mục tìm kiếm OpenSearch": kiểm truy vấn qua bí danh (alias) của
 * một tenant không bao giờ trả tài liệu mang {@code tenant_id} của tenant khác. Gọi REST API trực
 * tiếp (không kéo client OpenSearch), để DV-16 dùng lại với cụm thật hoặc Testcontainers.
 *
 * <p>Chỉ kiểm các API tìm kiếm ({@code _search}, {@code _count}): bộ lọc của alias không áp cho
 * {@code GET <alias>/_doc/<id>} và {@code _mget}, nên SVC-16 không được dùng hai API đó qua alias
 * của tenant (ghi ở README).
 */
public final class OpenSearchTenantLeakProbe {

  private static final int MAX_HITS = 10_000;

  private final URI endpoint;
  private final String tenantField;
  private final HttpClient http;
  private final ObjectMapper json = new ObjectMapper();

  public OpenSearchTenantLeakProbe(URI endpoint) {
    this(endpoint, "tenant_id");
  }

  public OpenSearchTenantLeakProbe(URI endpoint, String tenantField) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.tenantField = Objects.requireNonNull(tenantField, "tenantField");
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * Chạy bốn truy vấn qua {@code alias} của {@code ownTenant}: {@code match_all}, {@code term} theo
   * tenant kia, {@code ids} với mã tài liệu của tenant kia, gộp nhóm {@code terms} theo trường
   * tenant; ném {@link AssertionError} liệt kê mọi vi phạm. Alias rỗng cũng là lỗi (kiểm thử vô
   * nghĩa khi không có dữ liệu mồi).
   */
  public void assertAliasIsolated(
      String alias, String ownTenant, String otherTenant, Collection<String> otherTenantDocIds) {
    List<String> violations = new ArrayList<>();

    JsonNode all = search(alias, query("match_all", json.createObjectNode()), MAX_HITS);
    JsonNode hits = all.path("hits").path("hits");
    if (hits.isEmpty()) {
      violations.add(alias + ": match_all không trả tài liệu nào của " + ownTenant);
    }
    for (JsonNode hit : hits) {
      String tenant = hit.path("_source").path(tenantField).asText(null);
      if (!ownTenant.equals(tenant)) {
        violations.add(
            alias
                + ": match_all trả tài liệu "
                + hit.path("_id").asText()
                + " của tenant "
                + tenant);
      }
    }

    ObjectNode term = json.createObjectNode();
    term.put(tenantField, otherTenant);
    long byTerm = total(search(alias, query("term", term), 0));
    if (byTerm != 0) {
      violations.add(alias + ": term " + tenantField + "=" + otherTenant + " trả " + byTerm);
    }

    if (!otherTenantDocIds.isEmpty()) {
      ObjectNode ids = json.createObjectNode();
      ids.putPOJO("values", otherTenantDocIds);
      long byIds = total(search(alias, query("ids", ids), 0));
      if (byIds != 0) {
        violations.add(alias + ": truy vấn ids của tenant kia trả " + byIds + " tài liệu");
      }
    }

    ObjectNode aggs = json.createObjectNode();
    aggs.putObject("tenants").putObject("terms").put("field", tenantField).put("size", 100);
    ObjectNode body = query("match_all", json.createObjectNode());
    body.set("aggs", aggs);
    for (JsonNode bucket :
        search(alias, body, 0).path("aggregations").path("tenants").path("buckets")) {
      if (!ownTenant.equals(bucket.path("key").asText())) {
        violations.add(alias + ": gộp nhóm theo tenant lộ nhóm " + bucket.path("key").asText());
      }
    }

    if (!violations.isEmpty()) {
      throw new AssertionError(
          "RÒ RỈ TENANT qua OpenSearch:\n  " + String.join("\n  ", violations));
    }
  }

  private ObjectNode query(String type, JsonNode clause) {
    ObjectNode body = json.createObjectNode();
    body.putObject("query").set(type, clause);
    return body;
  }

  private static long total(JsonNode response) {
    return response.path("hits").path("total").path("value").asLong();
  }

  private JsonNode search(String alias, ObjectNode body, int size) {
    body.put("size", size);
    body.put("track_total_hits", true);
    return post("/" + alias + "/_search", body.toString());
  }

  /** Gọi REST tuỳ ý (dựng chỉ mục mẫu trong kiểm thử tự kiểm). */
  public JsonNode request(String method, String path, String body) {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header(
                "Content-Type",
                path.contains("_bulk") ? "application/x-ndjson" : "application/json");
    req.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    try {
      HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) {
        throw new IllegalStateException(
            method + " " + path + " trả " + res.statusCode() + ": " + res.body());
      }
      return json.readTree(res.body());
    } catch (IOException e) {
      throw new IllegalStateException("Không gọi được OpenSearch " + path, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bị ngắt khi gọi OpenSearch " + path, e);
    }
  }

  private JsonNode post(String path, String body) {
    return request("POST", path, body);
  }
}
