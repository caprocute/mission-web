package vn.viettel.okrkpi.oversight.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexReader;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexUnavailableException;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexWriter;
import vn.viettel.okrkpi.oversight.domain.AccessLogQuery;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Chỉ mục kiểm toán OpenSearch {@code audit-*} (TL-01 "Tách nhật ký kiểm toán", CORE-07-12). Tenant
 * silo và bridge có chỉ mục riêng theo tháng {@code audit-<tenant>-<yyyy.MM>}; tenant pool dùng chỉ
 * mục chung {@code audit-pool-<yyyy.MM>} có trường {@code tenant_id} (cùng cách TL-01 mô tả cho
 * SVC-16). Mọi truy vấn vẫn lọc {@code tenant_id} kể cả ở chỉ mục riêng. Một thể hiện chỉ dùng một
 * tài khoản: consumer giữ tài khoản ghi, API giữ tài khoản chỉ đọc (vai trò trong {@code
 * opensearch/audit/security-roles.json}).
 */
public class OpenSearchAuditIndex implements AuditIndexWriter, AuditIndexReader, AutoCloseable {

  static final String TEMPLATE = "opensearch/audit/index-template.json";
  static final String ISM_POLICY = "opensearch/audit/ism-policy.json";
  static final String POLICY_ID = "audit-luu-giu";

  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("yyyy.MM").withZone(ZoneOffset.UTC);

  private final ApacheHttpClient5Transport transport;
  private final OpenSearchGenericClient http;
  private final ObjectMapper json;
  private final Set<UUID> pooledTenants;

  public OpenSearchAuditIndex(
      URI endpoint,
      String username,
      String password,
      SSLContext sslContext,
      Set<UUID> pooledTenants,
      ObjectMapper json) {
    Objects.requireNonNull(endpoint, "endpoint");
    this.json = Objects.requireNonNull(json, "json");
    this.pooledTenants = Set.copyOf(pooledTenants);
    HttpHost host = new HttpHost(endpoint.getScheme(), endpoint.getHost(), endpoint.getPort());
    this.transport =
        ApacheHttpClient5TransportBuilder.builder(host)
            .setMapper(new JacksonJsonpMapper(json))
            .setHttpClientConfigCallback(
                b -> {
                  if (username != null && !username.isBlank()) {
                    BasicCredentialsProvider creds = new BasicCredentialsProvider();
                    creds.setCredentials(
                        new AuthScope(host),
                        new UsernamePasswordCredentials(
                            username, password == null ? new char[0] : password.toCharArray()));
                    b.setDefaultCredentialsProvider(creds);
                  }
                  if ("https".equals(endpoint.getScheme())) {
                    SSLContext ssl = sslContext;
                    if (ssl == null) {
                      try {
                        ssl = SSLContext.getDefault();
                      } catch (java.security.NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                      }
                    }
                    b.setConnectionManager(
                        PoolingAsyncClientConnectionManagerBuilder.create()
                            .setTlsStrategy(
                                ClientTlsStrategyBuilder.create().setSslContext(ssl).build())
                            .build());
                  }
                  return b;
                })
            .build();
    this.http = new OpenSearchClient(transport).generic();
  }

  /** Tên chỉ mục ghi của tenant trong tháng của {@code at}. */
  String indexFor(TenantId tenantId, Instant at) {
    return prefix(tenantId) + MONTH.format(at);
  }

  private String prefix(TenantId tenantId) {
    return pooledTenants.contains(tenantId.value())
        ? "audit-pool-"
        : "audit-" + tenantId.value() + "-";
  }

  /**
   * Đặt mẫu chỉ mục và chính sách vòng đời ISM (giữ nóng một năm, chuyển snapshot lạnh, xoá sau năm
   * năm). Gọi bằng tài khoản quản trị chỉ mục lúc triển khai hoặc trong kiểm thử.
   */
  public void installTemplates() {
    send("PUT", "/_index_template/audit", resource(TEMPLATE));
    JsonNode policy = resource(ISM_POLICY);
    Response r = raw("GET", "/_plugins/_ism/policies/" + POLICY_ID, null);
    if (r.getStatus() == 404) {
      send("PUT", "/_plugins/_ism/policies/" + POLICY_ID, policy);
    }
  }

  @Override
  public void index(List<AuditDocument> documents) {
    if (documents.isEmpty()) {
      return;
    }
    StringBuilder bulk = new StringBuilder();
    for (AuditDocument d : documents) {
      ObjectNode action = json.createObjectNode();
      action
          .putObject("index")
          .put("_index", indexFor(d.tenantId(), d.occurredAt()))
          .put("_id", d.docId());
      bulk.append(action).append('\n').append(source(d)).append('\n');
    }
    JsonNode result =
        execute(
            "POST",
            "/_bulk",
            Body.from(bulk.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson"));
    if (result.path("errors").asBoolean(false)) {
      for (JsonNode item : result.path("items")) {
        JsonNode error = item.path("index").path("error");
        if (!error.isMissingNode()) {
          throw new AuditIndexUnavailableException(
              "Chỉ mục kiểm toán từ chối tài liệu: " + error.path("type").asText(), null);
        }
      }
    }
  }

  @Override
  public List<AuditDocument> searchAccess(
      TenantId tenantId, AccessLogQuery query, After after, int limit) {
    ObjectNode body = json.createObjectNode();
    body.put("size", limit);
    body.put("track_total_hits", false);
    ArrayNode filter = body.putObject("query").putObject("bool").putArray("filter");
    term(filter, "tenant_id", tenantId.value().toString());
    term(filter, "action", AuditDocument.ACTION_C2_ACCESS);
    // Lần xem C2 luôn gắn đối tượng và cột; sự kiện thiếu một trong hai không thuộc nhật ký này.
    filter.addObject().putObject("exists").put("field", "column");
    filter.addObject().putObject("exists").put("field", "object_id");
    if (query.actorId() != null) {
      term(filter, "actor_id", query.actorId().toString());
    }
    if (query.objectType() != null) {
      term(filter, "object_type", query.objectType());
    }
    if (query.objectId() != null) {
      term(filter, "object_id", query.objectId());
    }
    if (query.from() != null || query.to() != null) {
      ObjectNode range = filter.addObject().putObject("range").putObject("occurred_at");
      if (query.from() != null) {
        range.put("gte", query.from().toString());
      }
      if (query.to() != null) {
        range.put("lt", query.to().toString());
      }
    }
    if (query.othersOnly()) {
      ((ObjectNode) body.path("query").path("bool"))
          .putArray("must_not")
          .addObject()
          .putObject("term")
          .put("self", true);
    }
    ArrayNode sort = body.putArray("sort");
    sort.addObject().put("occurred_at", "desc");
    sort.addObject().put("doc_id", "desc");
    if (after != null) {
      body.putArray("search_after").add(after.occurredAt().toEpochMilli()).add(after.docId());
    }
    JsonNode result =
        execute(
            "POST",
            "/" + prefix(tenantId) + "*/_search?ignore_unavailable=true&allow_no_indices=true",
            jsonBody(body));
    List<AuditDocument> out = new ArrayList<>();
    for (JsonNode hit : result.path("hits").path("hits")) {
      out.add(fromSource(hit.path("_source")));
    }
    return out;
  }

  private ObjectNode source(AuditDocument d) {
    ObjectNode s = json.createObjectNode();
    s.put("doc_id", d.docId());
    s.put("event_id", d.eventId().toString());
    s.put("tenant_id", d.tenantId().value().toString());
    s.put("occurred_at", d.occurredAt().toString());
    putIfPresent(s, "actor_id", d.actorId());
    putIfPresent(s, "actor_assignment_id", d.actorAssignmentId());
    putIfPresent(s, "actor_role", d.actorRole());
    s.put("action", d.action());
    putIfPresent(s, "outcome", d.outcome());
    putIfPresent(s, "object_type", d.objectType());
    putIfPresent(s, "object_id", d.objectId());
    putIfPresent(s, "column", d.column());
    putIfPresent(s, "purpose", d.purpose());
    if (d.self() != null) {
      s.put("self", d.self());
    }
    s.put("system", d.system());
    putIfPresent(s, "traceparent", d.traceparent());
    return s;
  }

  private static AuditDocument fromSource(JsonNode s) {
    return new AuditDocument(
        s.path("doc_id").asText(),
        UUID.fromString(s.path("event_id").asText()),
        TenantId.of(UUID.fromString(s.path("tenant_id").asText())),
        Instant.parse(s.path("occurred_at").asText()),
        uuid(s, "actor_id"),
        uuid(s, "actor_assignment_id"),
        text(s, "actor_role"),
        s.path("action").asText(),
        text(s, "outcome"),
        text(s, "object_type"),
        text(s, "object_id"),
        text(s, "column"),
        text(s, "purpose"),
        s.hasNonNull("self") ? s.get("self").asBoolean() : null,
        s.path("system").asBoolean(false),
        text(s, "traceparent"));
  }

  private static void putIfPresent(ObjectNode s, String field, Object value) {
    if (value != null) {
      s.put(field, value.toString());
    }
  }

  private static String text(JsonNode s, String field) {
    return s.hasNonNull(field) ? s.get(field).asText() : null;
  }

  private static UUID uuid(JsonNode s, String field) {
    return s.hasNonNull(field) ? UUID.fromString(s.get(field).asText()) : null;
  }

  private static void term(ArrayNode filter, String field, String value) {
    filter.addObject().putObject("term").put(field, value);
  }

  private void send(String method, String path, JsonNode body) {
    execute(method, path, jsonBody(body));
  }

  private Body jsonBody(JsonNode body) {
    return Body.from(body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
  }

  private JsonNode execute(String method, String path, Body body) {
    Response r = raw(method, path, body);
    String text = r.getBody().map(Body::bodyAsString).orElse("{}");
    if (r.getStatus() >= 300) {
      throw new AuditIndexUnavailableException(
          "OpenSearch trả " + r.getStatus() + " cho " + method + " " + strip(path), null);
    }
    try {
      return json.readTree(text);
    } catch (IOException e) {
      throw new AuditIndexUnavailableException("Phản hồi OpenSearch không đọc được", e);
    }
  }

  private Response raw(String method, String path, Body body) {
    try {
      return http.execute(Requests.create(method, path, List.of(), Map.of(), body));
    } catch (IOException e) {
      throw new AuditIndexUnavailableException("Không kết nối được chỉ mục kiểm toán", e);
    }
  }

  /** Không đưa mã tenant trong đường dẫn vào thông điệp lỗi. */
  private static String strip(String path) {
    int q = path.indexOf('?');
    String p = q < 0 ? path : path.substring(0, q);
    return p.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<tenant>");
  }

  private JsonNode resource(String name) {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu tài nguyên " + name);
      }
      return json.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Không đọc được " + name, e);
    }
  }

  @Override
  public void close() throws IOException {
    transport.close();
  }
}
