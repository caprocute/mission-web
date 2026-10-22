package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.analytics.application.AnalyticsErrors;
import vn.viettel.okrkpi.analytics.application.CubePort;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Cube REST API ({@code /cubejs-api/v1/load}, {@code /cubejs-api/v1/meta}) qua {@link
 * ResilientClient}. Cube trả {@code "Continue wait"} khi truy vấn còn chạy; client hỏi lại tới hết
 * thời gian chờ.
 *
 * <p>Lớp ngữ nghĩa dựng từ {@code meta?extended=true}: chỉ thành viên công khai, tiêu đề ngắn,
 * {@code format: percent} thành đơn vị {@code %}, phiên bản lấy từ {@code meta.phien_ban} của các
 * cube; chiều của cube C hỗ trợ chỉ số của cube M khi C là M hoặc đi được từ M theo {@code joins}.
 */
final class CubeRestClient implements CubePort {

  private static final Logger LOG = LoggerFactory.getLogger(CubeRestClient.class);
  private static final String CONTINUE_WAIT = "Continue wait";
  private static final Duration POLL = Duration.ofMillis(200);

  private final ResilientClient http;
  private final CubeTokens tokens;
  private final ObjectMapper json;
  private final Duration timeout;
  private final Clock clock;

  CubeRestClient(
      ResilientClient http, CubeTokens tokens, ObjectMapper json, Duration timeout, Clock clock) {
    this.http = http;
    this.tokens = tokens;
    this.json = json;
    this.timeout = timeout;
    this.clock = clock;
  }

  @Override
  public List<Map<String, Object>> load(Scope scope, Map<String, Object> query) {
    Map<String, Object> body = Map.of("query", query);
    Instant deadline = clock.instant().plus(timeout);
    while (true) {
      JsonNode res = send(HttpMethod.POST, "/cubejs-api/v1/load", body, scope);
      if (CONTINUE_WAIT.equals(res.path("error").asText(null))) {
        if (clock.instant().isAfter(deadline)) {
          throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, null);
        }
        pause();
        continue;
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (JsonNode row : res.path("data")) {
        Map<String, Object> r = new LinkedHashMap<>();
        row.fields()
            .forEachRemaining(
                e -> r.put(e.getKey(), e.getValue().isNull() ? null : scalar(e.getValue())));
        rows.add(r);
      }
      return rows;
    }
  }

  @Override
  public SemanticCatalog model(Scope scope) {
    return catalog(send(HttpMethod.GET, "/cubejs-api/v1/meta?extended=true", null, scope));
  }

  static SemanticCatalog catalog(JsonNode meta) {
    Map<String, Set<String>> joins = new HashMap<>();
    Map<String, List<String>> cubeMeasures = new HashMap<>();
    Map<String, SemanticCatalog.Measure> measures = new LinkedHashMap<>();
    List<String[]> dims = new ArrayList<>();
    Map<String, JsonNode> dimNodes = new HashMap<>();
    String version = null;
    for (JsonNode cube : meta.path("cubes")) {
      String name = cube.path("name").asText();
      if (!visible(cube)) {
        continue;
      }
      String v = cube.path("meta").path("phien_ban").asText(null);
      if (v != null && (version == null || v.compareTo(version) > 0)) {
        version = v;
      }
      Set<String> to = new HashSet<>();
      for (JsonNode j : cube.path("joins")) {
        to.add(j.isTextual() ? j.asText() : j.path("name").asText());
      }
      joins.put(name, to);
      List<String> own = new ArrayList<>();
      for (JsonNode m : cube.path("measures")) {
        String n = m.path("name").asText();
        if (!visible(m) || n.endsWith(".tenant_id")) {
          continue;
        }
        own.add(n);
        measures.put(
            n,
            new SemanticCatalog.Measure(
                n,
                title(m),
                m.path("type").asText("number"),
                "percent".equals(m.path("format").asText(null)) ? "%" : null,
                m.path("drillMembers").isArray() && !m.path("drillMembers").isEmpty()));
      }
      cubeMeasures.put(name, own);
      for (JsonNode d : cube.path("dimensions")) {
        String n = d.path("name").asText();
        if (!visible(d) || n.endsWith(".tenant_id")) {
          continue;
        }
        dims.add(new String[] {name, n});
        dimNodes.put(n, d);
      }
    }
    // Cube của chiều -> chỉ số đi tới được cube đó.
    Map<String, Set<String>> supported = new HashMap<>();
    for (String from : cubeMeasures.keySet()) {
      for (String reached : reachable(from, joins)) {
        supported
            .computeIfAbsent(reached, k -> new java.util.LinkedHashSet<>())
            .addAll(cubeMeasures.get(from));
      }
    }
    Map<String, SemanticCatalog.Dimension> dimensions = new LinkedHashMap<>();
    for (String[] d : dims) {
      JsonNode node = dimNodes.get(d[1]);
      dimensions.put(
          d[1],
          new SemanticCatalog.Dimension(
              d[1],
              title(node),
              node.path("type").asText("string"),
              Set.copyOf(supported.getOrDefault(d[0], Set.of()))));
    }
    return new SemanticCatalog(version == null ? "0" : version, measures, dimensions);
  }

  private static Set<String> reachable(String from, Map<String, Set<String>> joins) {
    Set<String> seen = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>(List.of(from));
    while (!todo.isEmpty()) {
      String c = todo.pop();
      if (seen.add(c)) {
        todo.addAll(joins.getOrDefault(c, Set.of()));
      }
    }
    return seen;
  }

  private static boolean visible(JsonNode n) {
    return n.path("isVisible").asBoolean(true) && n.path("public").asBoolean(true);
  }

  private static String title(JsonNode n) {
    String s = n.path("shortTitle").asText(null);
    return s != null && !s.isBlank() ? s : n.path("title").asText(n.path("name").asText());
  }

  private static Object scalar(JsonNode v) {
    return v.isNumber() ? v.decimalValue() : v.isBoolean() ? v.booleanValue() : v.asText();
  }

  /**
   * Gọi Cube qua {@link ResilientClient} (Resilience4j: ngắt mạch, giới hạn thời gian, vách ngăn;
   * GET được thử lại). 400 là truy vấn không chạy được trên lớp ngữ nghĩa, 403 là ngoài phạm vi.
   */
  private JsonNode send(HttpMethod method, String uri, Object body, Scope scope) {
    HttpHeaders h = new HttpHeaders();
    h.set(HttpHeaders.AUTHORIZATION, tokens.issue(scope));
    if (body != null) {
      h.setContentType(MediaType.APPLICATION_JSON);
    }
    JsonNode res;
    try {
      res = http.exchange(method, uri, body, h, JsonNode.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 400) {
        LOG.debug("Cube từ chối truy vấn: {}", e.getResponseBodyAsString(StandardCharsets.UTF_8));
        throw new BusinessException(
            AnalyticsErrors.INVALID_COMBINATION,
            "Chỉ số và chiều đã chọn không kết hợp được với nhau");
      }
      if (status == 403) {
        throw AnalyticsErrors.outOfScope("Ngoài phạm vi dữ liệu được xem");
      }
      LOG.warn("Cube trả mã {}", status);
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, e);
    }
    if (res == null) {
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, null);
    }
    return res;
  }

  private static void pause() {
    try {
      Thread.sleep(POLL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, e);
    }
  }

  private static DependencyUnavailableException unavailable(
      DependencyUnavailableException.Reason reason, Throwable cause) {
    return new DependencyUnavailableException("cube", reason, Duration.ofSeconds(5), cause);
  }
}
