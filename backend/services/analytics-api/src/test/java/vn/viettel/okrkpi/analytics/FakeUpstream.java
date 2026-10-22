package vn.viettel.okrkpi.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cube REST, Keycloak (client credentials) và cổng dịch vụ MOD-08 {@code /config/dashboard-defs}
 * giả lập trên một HttpServer của JDK, theo hành vi đã chốt ở DV-15-03 và CORE-08-17.
 */
final class FakeUpstream implements AutoCloseable {

  static final String CUBE_SECRET = "khoa-ky-cube-cho-kiem-thu-tich-hop-0001";
  static final String SERVICE_TOKEN = "token-dich-vu-analytics";
  static final String SUPERSET_USER = "analytics_api";
  static final String SUPERSET_PASSWORD = "mat-khau-superset-kiem-thu";
  static final String SUPERSET_ACCESS = "token-truy-cap-superset";

  static final String META =
      """
      {"cubes": [
        {"name": "okr", "title": "Mục tiêu O", "meta": {"phien_ban": "2026.4"},
         "joins": [{"name": "org_unit"}, {"name": "period"}],
         "measures": [
           {"name": "okr.tien_do_o", "title": "Mục tiêu O Tiến độ mục tiêu O", "shortTitle": "Tiến độ mục tiêu O",
            "type": "number", "format": "percent"},
           {"name": "okr.so_muc_tieu", "shortTitle": "Số mục tiêu O", "type": "count"}],
         "dimensions": [
           {"name": "okr.tenant_id", "type": "string", "public": false},
           {"name": "okr.chu_the", "shortTitle": "Chủ thể mục tiêu", "type": "string"}]},
        {"name": "org_unit", "meta": {"phien_ban": "2026.4"}, "measures": [],
         "dimensions": [{"name": "org_unit.ten", "shortTitle": "Tên đơn vị", "type": "string"}]},
        {"name": "period", "meta": {"phien_ban": "2026.4"}, "measures": [],
         "dimensions": [{"name": "period.id", "shortTitle": "Mã định danh kỳ", "type": "string"}]},
        {"name": "task", "meta": {"phien_ban": "2026.4"}, "joins": [{"name": "org_unit"}],
         "measures": [{"name": "task.so_nhiem_vu", "shortTitle": "Số nhiệm vụ", "type": "count"}],
         "dimensions": [{"name": "task.han", "shortTitle": "Hạn hoàn thành", "type": "time"}]},
        {"name": "data_freshness", "meta": {"phien_ban": "2026.4"},
         "measures": [{"name": "data_freshness.cap_nhat_luc", "shortTitle": "Số liệu cập nhật lúc", "type": "max"}],
         "dimensions": []}
      ]}
      """;

  /** Một truy vấn Cube đã nhận: ngữ cảnh an ninh trong token và truy vấn. */
  record CubeCall(String tenant, String orgPath, JsonNode query) {}

  private final ObjectMapper json = new ObjectMapper();
  private final HttpServer server;
  final List<CubeCall> cubeCalls = Collections.synchronizedList(new ArrayList<>());
  final List<Map<String, String>> configHeaders = Collections.synchronizedList(new ArrayList<>());
  final Map<String, ObjectNode> dashboards = Collections.synchronizedMap(new LinkedHashMap<>());
  final List<JsonNode> guestRequests = Collections.synchronizedList(new ArrayList<>());

  /** API chi tiết của mission-core: đường dẫn (không kèm truy vấn) → mã trạng thái và thân. */
  final Map<String, Object[]> core = Collections.synchronizedMap(new LinkedHashMap<>());

  /** Header Authorization của mỗi lần gọi API chi tiết. */
  final List<String> coreAuthorizations = Collections.synchronizedList(new ArrayList<>());

  final java.util.concurrent.atomic.AtomicInteger supersetLogins =
      new java.util.concurrent.atomic.AtomicInteger();
  private final AtomicBoolean waited = new AtomicBoolean();

  FakeUpstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/mission/protocol/openid-connect/token", this::token);
    server.createContext("/cubejs-api/v1/", this::cube);
    server.createContext("/api/v1/config/dashboard-defs", this::config);
    server.createContext("/api/v1/security/login", this::supersetLogin);
    for (String prefix :
        List.of(
            "/api/v1/tasks/",
            "/api/v1/products",
            "/api/v1/evidences/",
            "/api/v1/plans",
            "/api/v1/okr/")) {
      server.createContext(prefix, this::coreDetail);
    }
    server.createContext("/api/v1/security/guest_token/", this::supersetGuest);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<CubeCall> dataCalls() {
    synchronized (cubeCalls) {
      return cubeCalls.stream()
          .filter(c -> !c.query().path("measures").toString().contains("data_freshness"))
          .toList();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void token(HttpExchange ex) throws IOException {
    String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (!form.contains("grant_type=client_credentials")
        || !form.contains("client_id=analytics-api")) {
      send(ex, 401, "{}");
      return;
    }
    send(ex, 200, "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"expires_in\":300}");
  }

  /** API chi tiết của mission-core: chỉ nhận token người xem, không nhận token dịch vụ. */
  private void coreDetail(HttpExchange ex) throws IOException {
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    coreAuthorizations.add(auth);
    if (auth == null || auth.equals("Bearer " + SERVICE_TOKEN)) {
      send(ex, 401, "{}");
      return;
    }
    Object[] r = core.get(ex.getRequestURI().getPath());
    if (r == null) {
      problem(ex, 404, null, "Không có");
      return;
    }
    if ((int) r[0] == 403) {
      problem(ex, 403, "COMMON-403-OUT-OF-SCOPE", "Ngoài phạm vi");
      return;
    }
    send(ex, (int) r[0], (String) r[1]);
  }

  /** Superset 4.x {@code /api/v1/security/login}: chỉ tài khoản dịch vụ, nhà cung cấp db. */
  private void supersetLogin(HttpExchange ex) throws IOException {
    JsonNode body = json.readTree(ex.getRequestBody());
    if (!SUPERSET_USER.equals(body.path("username").asText())
        || !SUPERSET_PASSWORD.equals(body.path("password").asText())
        || !"db".equals(body.path("provider").asText())) {
      send(ex, 401, "{\"message\":\"Invalid login\"}");
      return;
    }
    supersetLogins.incrementAndGet();
    send(ex, 200, "{\"access_token\":\"" + SUPERSET_ACCESS + "\"}");
  }

  /** Superset 4.x {@code /api/v1/security/guest_token/}: ghi lại yêu cầu, trả token khách. */
  private void supersetGuest(HttpExchange ex) throws IOException {
    if (!("Bearer " + SUPERSET_ACCESS).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      send(ex, 401, "{\"msg\":\"Token has expired\"}");
      return;
    }
    JsonNode body = json.readTree(ex.getRequestBody());
    guestRequests.add(body);
    send(ex, 200, "{\"token\":\"token-khach-" + guestRequests.size() + "\"}");
  }

  private void cube(HttpExchange ex) throws IOException {
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(ex.getRequestHeaders().getFirst("Authorization"));
      if (!jwt.verify(new MACVerifier(CUBE_SECRET.getBytes(StandardCharsets.UTF_8)))
          || jwt.getJWTClaimsSet().getExpirationTime().before(new java.util.Date())) {
        send(ex, 403, "{\"error\":\"Token không hợp lệ\"}");
        return;
      }
    } catch (Exception e) {
      send(ex, 403, "{\"error\":\"Token không hợp lệ\"}");
      return;
    }
    String path = ex.getRequestURI().getPath();
    if (path.endsWith("/meta")) {
      send(ex, 200, META);
      return;
    }
    JsonNode query = json.readTree(ex.getRequestBody().readAllBytes()).path("query");
    String tenant;
    String orgPath;
    try {
      tenant = jwt.getJWTClaimsSet().getStringClaim("tenant");
      orgPath = jwt.getJWTClaimsSet().getStringClaim("org_path");
    } catch (java.text.ParseException e) {
      send(ex, 403, "{}");
      return;
    }
    if (!waited.getAndSet(true)) {
      send(ex, 200, "{\"error\":\"Continue wait\"}");
      return;
    }
    cubeCalls.add(new CubeCall(tenant, orgPath, query));
    ObjectNode row = json.createObjectNode();
    if (query.path("measures").toString().contains("data_freshness.cap_nhat_luc")) {
      row.put(
          "data_freshness.cap_nhat_luc",
          String.valueOf(Instant.now().minusSeconds(20).toEpochMilli()));
    } else {
      query.path("dimensions").forEach(d -> row.put(d.asText(), "Huyện A"));
      query
          .path("timeDimensions")
          .forEach(
              t ->
                  row.put(
                      t.path("dimension").asText() + "." + t.path("granularity").asText(),
                      "2026-11-09T00:00:00.000"));
      boolean previous = query.path("filters").toString().contains(AnalyticsApiIT.KY_Q3);
      query.path("measures").forEach(m -> row.put(m.asText(), previous ? "61.5" : "72.5"));
    }
    ObjectNode body = json.createObjectNode();
    body.putArray("data").add(row);
    send(ex, 200, json.writeValueAsString(body));
  }

  private void config(HttpExchange ex) throws IOException {
    Map<String, String> h = new LinkedHashMap<>();
    ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
    configHeaders.add(h);
    if (!("Bearer " + SERVICE_TOKEN).equals(h.get("authorization"))
        || h.get("x-acting-user") == null) {
      send(ex, 401, "{}");
      return;
    }
    Map<String, List<String>> q = query(ex.getRequestURI().getRawQuery());
    String tenant = first(q, "tenant");
    String actor = h.get("x-acting-user");
    List<String> viewerUnits = q.getOrDefault("viewerUnit", List.of());
    String path = ex.getRequestURI().getPath();
    String id = path.endsWith("dashboard-defs") ? null : path.substring(path.lastIndexOf('/') + 1);
    String method = ex.getRequestMethod();
    if (id == null && "POST".equals(method)) {
      ObjectNode in = (ObjectNode) json.readTree(ex.getRequestBody().readAllBytes());
      boolean dup =
          dashboards.values().stream()
              .anyMatch(
                  d ->
                      d.path("ownerUserId").asText().equals(actor)
                          && d.path("title").asText().equals(in.path("title").asText()));
      if (dup) {
        problem(ex, 409, "CONFIG-409-DUPLICATE-TITLE", "Đã có dashboard trùng tên");
        return;
      }
      ObjectNode d = stored(UUID.randomUUID().toString(), tenant, actor, in, 0);
      dashboards.put(d.path("id").asText(), d);
      send(ex, 201, json.writeValueAsString(view(d)));
      return;
    }
    if (id == null) {
      ArrayNode items = json.createArrayNode();
      for (ObjectNode d : dashboards.values()) {
        if (visible(d, tenant, actor, viewerUnits)
            && (!"me".equals(first(q, "owner")) || d.path("ownerUserId").asText().equals(actor))) {
          ObjectNode s = json.createObjectNode();
          s.put("id", d.path("id").asText());
          s.put("title", d.path("title").asText());
          s.put("ownerUserId", d.path("ownerUserId").asText());
          s.put("shareScope", d.path("shareScope").asText());
          s.put("widgetCount", d.path("layout").size());
          s.put("updatedAt", d.path("updatedAt").asText());
          items.add(s);
        }
      }
      ObjectNode page = json.createObjectNode();
      page.set("items", items);
      page.putNull("nextCursor");
      send(ex, 200, json.writeValueAsString(page));
      return;
    }
    ObjectNode cur = dashboards.get(id);
    boolean get = "GET".equals(method);
    // Như CORE-08-17: xem theo phạm vi chia sẻ; sửa chỉ người tạo (403 CONFIG-403-NOT-OWNER).
    if (cur == null
        || (get
            ? !visible(cur, tenant, actor, viewerUnits)
            : !cur.path("tenant").asText().equals(tenant))) {
      problem(ex, 404, null, "Không tìm thấy");
      return;
    }
    if (get) {
      send(ex, 200, json.writeValueAsString(view(cur)));
      return;
    }
    if (!cur.path("ownerUserId").asText().equals(actor)) {
      problem(ex, 403, "CONFIG-403-NOT-OWNER", "Chỉ người tạo được sửa");
      return;
    }
    String ifMatch = h.get("if-match");
    if (ifMatch == null) {
      problem(ex, 428, null, "Thiếu If-Match");
      return;
    }
    if (!ifMatch.equals("\"" + cur.path("rowVersion").asLong() + "\"")) {
      problem(ex, 409, "CONFIG-409-VERSION-CONFLICT", "Dữ liệu đã được người khác cập nhật");
      return;
    }
    ObjectNode in = (ObjectNode) json.readTree(ex.getRequestBody().readAllBytes());
    ObjectNode d = stored(id, tenant, actor, in, cur.path("rowVersion").asLong() + 1);
    dashboards.put(id, d);
    send(ex, 200, json.writeValueAsString(view(d)));
  }

  private ObjectNode stored(String id, String tenant, String owner, ObjectNode in, long version) {
    ObjectNode d = json.createObjectNode();
    d.put("id", id);
    d.put("tenant", tenant);
    d.put("title", in.path("title").asText());
    d.put("ownerUserId", owner);
    d.set("layout", in.path("layout"));
    d.put("shareScope", in.path("shareScope").asText("RIENG"));
    d.set(
        "sharedUnitIds",
        in.has("sharedUnitIds") ? in.path("sharedUnitIds") : json.createArrayNode());
    d.put("semanticModelVersion", in.path("semanticModelVersion").asText());
    d.put("rowVersion", version);
    d.put("updatedAt", "2026-11-16T09:00:00+07:00");
    return d;
  }

  private ObjectNode view(ObjectNode d) {
    ObjectNode v = d.deepCopy();
    v.remove("tenant");
    ArrayNode ms = v.putArray("measures");
    ArrayNode ds = v.putArray("dimensions");
    d.path("layout")
        .forEach(
            w -> {
              ms.add(w.path("measure").asText());
              w.path("dimensions").forEach(ds::add);
            });
    return v;
  }

  private static boolean visible(ObjectNode d, String tenant, String actor, List<String> units) {
    if (!d.path("tenant").asText().equals(tenant)) {
      return false;
    }
    if (d.path("ownerUserId").asText().equals(actor)
        || "TENANT".equals(d.path("shareScope").asText())) {
      return true;
    }
    if ("DON_VI".equals(d.path("shareScope").asText())) {
      for (JsonNode u : d.path("sharedUnitIds")) {
        if (units.contains(u.asText())) {
          return true;
        }
      }
    }
    return false;
  }

  private void problem(HttpExchange ex, int status, String code, String detail) throws IOException {
    ObjectNode p = json.createObjectNode();
    p.put("type", "https://mission.gov.vn/problems/test");
    p.put("title", detail);
    p.put("status", status);
    p.put("detail", detail);
    if (code != null) {
      p.put("code", code);
    }
    ex.getResponseHeaders().add("Content-Type", "application/problem+json");
    byte[] b = json.writeValueAsBytes(p);
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  private static Map<String, List<String>> query(String raw) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (raw != null) {
      for (String kv : raw.split("&")) {
        String[] p = kv.split("=", 2);
        out.computeIfAbsent(URLDecoder.decode(p[0], StandardCharsets.UTF_8), k -> new ArrayList<>())
            .add(p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
      }
    }
    return out;
  }

  private static String first(Map<String, List<String>> q, String k) {
    List<String> v = q.get(k);
    return v == null || v.isEmpty() ? null : v.get(0);
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }
}
