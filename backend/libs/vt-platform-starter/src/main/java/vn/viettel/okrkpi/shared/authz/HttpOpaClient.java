package vn.viettel.okrkpi.shared.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hiện thực {@link OpaClient} gọi OPA cùng pod qua HTTP cục bộ, tuân thủ timeout TL-01 (50 ms
 * connect, 100 ms read), từ chối khi lỗi/quá hạn (fail closed), ghi metric Micrometer và đệm quyết
 * định theo {@code (người dùng, hành động, tài nguyên, phiên bản bundle)} (NEN-014).
 */
public final class HttpOpaClient implements OpaClient {

  private static final Logger log = LoggerFactory.getLogger(HttpOpaClient.class);

  private final OpaProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final AtomicReference<String> bundleRevision = new AtomicReference<>("v1");
  private final ConcurrentHashMap<String, CacheEntry> decisionCache = new ConcurrentHashMap<>();

  public HttpOpaClient(
      OpaProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
    this(properties, objectMapper, meterRegistry, Clock.systemUTC());
  }

  public HttpOpaClient(
      OpaProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.objectMapper =
        objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  @Override
  public OpaDecision decide(OpaInput input) {
    Objects.requireNonNull(input, "input must not be null");
    long startNanos = System.nanoTime();
    String rev = bundleRevision.get();
    Duration ttl = properties.getDecisionCacheTtl();
    boolean cacheEnabled = ttl != null && !ttl.isZero() && !ttl.isNegative();
    String key = cacheEnabled ? input.cacheKey(rev) : null;

    if (cacheEnabled && key != null) {
      CacheEntry cached = decisionCache.get(key);
      if (cached != null) {
        if (clock.instant().isBefore(cached.expiresAt())) {
          OpaDecision hit = cached.decision().asCached();
          recordMetrics(startNanos, true, hit.isPermitted() ? "allow" : "deny", primaryReason(hit));
          return hit;
        }
        decisionCache.remove(key, cached);
      }
    }

    try {
      byte[] payload = objectMapper.writeValueAsBytes(Map.of("input", input));
      URI uri = resolveUri(properties.getDecisionPath());
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(properties.getReadTimeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build();

      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        log.warn(
            "OPA trả mã trạng thái {} tại {}, từ chối theo nguyên tắc fail-closed",
            response.statusCode(),
            uri);
        recordMetrics(startNanos, false, "error", "opa_http_" + response.statusCode());
        return OpaDecision.denyClosed("opa_unavailable");
      }

      OpaDecision decision = parseDecision(response.body(), rev);
      if (cacheEnabled && key != null && !"error".equals(decision.bundleRevision())) {
        evictIfFull();
        decisionCache.put(key, new CacheEntry(decision, clock.instant().plus(ttl)));
      }
      recordMetrics(
          startNanos, false, decision.isPermitted() ? "allow" : "deny", primaryReason(decision));
      return decision;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Luồng bị ngắt khi gọi OPA, từ chối theo nguyên tắc fail-closed", e);
      recordMetrics(startNanos, false, "error", "opa_interrupted");
      return OpaDecision.denyClosed("opa_unavailable");
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Lỗi hoặc quá hạn khi gọi OPA ({}), từ chối theo nguyên tắc fail-closed", e.getMessage());
      recordMetrics(startNanos, false, "error", "opa_unavailable");
      return OpaDecision.denyClosed("opa_unavailable");
    }
  }

  @Override
  public JsonNode compile(String query, Map<String, Object> input, List<String> unknowns) {
    Objects.requireNonNull(query, "query must not be null");
    long startNanos = System.nanoTime();
    try {
      Map<String, Object> body =
          Map.of(
              "query", query,
              "input", input == null ? Map.of() : input,
              "unknowns", unknowns == null ? List.of("input.resource") : unknowns);
      byte[] payload = objectMapper.writeValueAsBytes(body);
      URI uri = resolveUri(properties.getCompilePath());
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(properties.getReadTimeout())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build();

      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        recordCompileMetric(startNanos, "error");
        throw new UntranslatablePolicyException(
            "OPA /v1/compile trả mã trạng thái " + response.statusCode());
      }
      JsonNode root = objectMapper.readTree(response.body());
      JsonNode result = root.get("result");
      if (result == null || result.isNull()) {
        recordCompileMetric(startNanos, "error");
        throw new UntranslatablePolicyException("OPA /v1/compile không có trường result");
      }
      recordCompileMetric(startNanos, "ok");
      return result;
    } catch (UntranslatablePolicyException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      recordCompileMetric(startNanos, "error");
      throw new UntranslatablePolicyException("Luồng bị ngắt khi gọi OPA /v1/compile", e);
    } catch (IOException | RuntimeException e) {
      recordCompileMetric(startNanos, "error");
      throw new UntranslatablePolicyException(
          "Không gọi được OPA /v1/compile: " + e.getMessage(), e);
    }
  }

  @Override
  public String currentBundleRevision() {
    return bundleRevision.get();
  }

  @Override
  public void invalidateCache(String newBundleRevision) {
    if (newBundleRevision != null && !newBundleRevision.isBlank()) {
      bundleRevision.set(newBundleRevision);
    }
    decisionCache.clear();
  }

  public int cacheSize() {
    return decisionCache.size();
  }

  private OpaDecision parseDecision(byte[] body, String fallbackRevision) throws IOException {
    JsonNode root = objectMapper.readTree(body);
    JsonNode result = root.get("result");
    if (result == null || result.isNull()) {
      return OpaDecision.denyClosed("opa_empty_result");
    }
    // Hỗ trợ cả /v1/data/okrkpi/authz/decision (trả trực tiếp {allow, deny, flags})
    // và /v1/data/okrkpi/authz (trả {decision: {allow, deny, flags}}).
    JsonNode decisionNode = result.has("decision") ? result.get("decision") : result;
    if (decisionNode == null || !decisionNode.isObject()) {
      return OpaDecision.denyClosed("opa_invalid_result");
    }
    boolean allow = decisionNode.path("allow").asBoolean(false);
    Set<String> deny = readStringSet(decisionNode.get("deny"));
    Set<String> flags = readStringSet(decisionNode.get("flags"));
    return new OpaDecision(allow, deny, flags, fallbackRevision, false);
  }

  private static Set<String> readStringSet(JsonNode node) {
    if (node == null || !node.isArray()) {
      return Set.of();
    }
    Set<String> set = new LinkedHashSet<>();
    for (JsonNode item : node) {
      if (item != null && item.isTextual()) {
        set.add(item.asText());
      }
    }
    return Set.copyOf(set);
  }

  private URI resolveUri(String path) {
    String base = properties.getBaseUrl().toString();
    if (base.endsWith("/") && path.startsWith("/")) {
      return URI.create(base.substring(0, base.length() - 1) + path);
    }
    if (!base.endsWith("/") && !path.startsWith("/")) {
      return URI.create(base + "/" + path);
    }
    return URI.create(base + path);
  }

  private void evictIfFull() {
    if (decisionCache.size() < properties.getMaxCacheEntries()) {
      return;
    }
    Instant now = clock.instant();
    decisionCache.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
    if (decisionCache.size() >= properties.getMaxCacheEntries()) {
      var it = decisionCache.keySet().iterator();
      if (it.hasNext()) {
        decisionCache.remove(it.next());
      }
    }
  }

  private static String primaryReason(OpaDecision decision) {
    if (decision.isPermitted()) {
      return "allowed";
    }
    return decision.deny().stream().findFirst().orElse("denied");
  }

  private void recordMetrics(long startNanos, boolean cached, String outcome, String reason) {
    long elapsedNanos = System.nanoTime() - startNanos;
    Timer.builder("okrkpi.opa.decision.latency")
        .tag("cached", Boolean.toString(cached))
        .tag("outcome", outcome)
        .register(meterRegistry)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
    Counter.builder("okrkpi.opa.decision.total")
        .tag("cached", Boolean.toString(cached))
        .tag("outcome", outcome)
        .tag("reason", reason)
        .register(meterRegistry)
        .increment();
  }

  private void recordCompileMetric(long startNanos, String outcome) {
    long elapsedNanos = System.nanoTime() - startNanos;
    Timer.builder("okrkpi.opa.compile.latency")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .record(elapsedNanos, TimeUnit.NANOSECONDS);
  }

  private record CacheEntry(OpaDecision decision, Instant expiresAt) {}
}
