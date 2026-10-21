package vn.viettel.okrkpi.shared.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triển khai {@link TenantRegistryClient} gọi HTTP {@code GET /api/v1/registry/resolve} của {@code
 * tenant-registry} (SVC-19) theo hợp đồng {@code contracts/openapi/platform.yaml}.
 */
public final class HttpTenantRegistryClient implements TenantRegistryClient {

  private static final Logger log = LoggerFactory.getLogger(HttpTenantRegistryClient.class);
  private static final long DEFAULT_TTL_SECONDS = 300L;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final Duration requestTimeout;
  private final Clock clock;

  public HttpTenantRegistryClient(
      String baseUrl, Duration requestTimeout, ObjectMapper objectMapper) {
    this(
        HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
        objectMapper,
        baseUrl,
        requestTimeout,
        Clock.systemUTC());
  }

  public HttpTenantRegistryClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      String baseUrl,
      Duration requestTimeout,
      Clock clock) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Optional<TenantCellRoute> resolveByTenantId(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    return queryResolve("tenantId", tenantId.toString());
  }

  @Override
  public Optional<TenantCellRoute> resolveByCode(String tenantCode) {
    Objects.requireNonNull(tenantCode, "tenantCode must not be null");
    return queryResolve("code", tenantCode);
  }

  @Override
  public Optional<TenantCellRoute> resolveByDomain(String domain) {
    Objects.requireNonNull(domain, "domain must not be null");
    return queryResolve("domain", domain);
  }

  private Optional<TenantCellRoute> queryResolve(String paramName, String paramValue) {
    String encoded = URLEncoder.encode(paramValue, StandardCharsets.UTF_8);
    URI uri = URI.create(baseUrl + "/api/v1/registry/resolve?" + paramName + "=" + encoded);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int status = response.statusCode();
      if (status == 404) {
        return Optional.empty();
      }
      if (status < 200 || status >= 300) {
        log.warn(
            "tenant-registry resolve failed with HTTP {} for {}={}", status, paramName, paramValue);
        return Optional.empty();
      }
      ResolutionPayload payload = objectMapper.readValue(response.body(), ResolutionPayload.class);
      return Optional.of(toRoute(payload));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while resolving tenant {}={}", paramName, paramValue, ex);
      return Optional.empty();
    } catch (IOException ex) {
      log.warn("I/O error while resolving tenant {}={}", paramName, paramValue, ex);
      return Optional.empty();
    }
  }

  private TenantCellRoute toRoute(ResolutionPayload payload) {
    UUID tenantId =
        payload.tenantId() != null && !payload.tenantId().isBlank()
            ? UUID.fromString(payload.tenantId())
            : null;
    TenancyModel model = TenancyModel.fromString(payload.tenancyModel());
    String cellCode =
        payload.cell() != null && payload.cell().code() != null
            ? payload.cell().code()
            : "cell-shared-01";
    String cellApiBaseUrl = payload.cell() != null ? payload.cell().apiBaseUrl() : null;
    String dbName =
        payload.databaseName() != null && !payload.databaseName().isBlank()
            ? payload.databaseName()
            : model.defaultDatabaseName(payload.tenantCode());
    long ttl =
        payload.cacheTtlSeconds() != null && payload.cacheTtlSeconds() > 0
            ? payload.cacheTtlSeconds()
            : DEFAULT_TTL_SECONDS;
    long version = payload.sourceVersion() != null ? payload.sourceVersion() : 0L;
    Instant expiresAt = clock.instant().plusSeconds(ttl);
    return new TenantCellRoute(
        tenantId,
        payload.tenantCode(),
        cellCode,
        cellApiBaseUrl,
        model,
        dbName,
        payload.status(),
        payload.domains(),
        version,
        expiresAt);
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ResolutionPayload(
      String tenantId,
      String tenantCode,
      String tenantName,
      String status,
      String tenancyModel,
      CellPayload cell,
      String databaseName,
      List<String> domains,
      Long cacheTtlSeconds,
      Long sourceVersion) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CellPayload(String id, String code, String apiBaseUrl) {}
}
