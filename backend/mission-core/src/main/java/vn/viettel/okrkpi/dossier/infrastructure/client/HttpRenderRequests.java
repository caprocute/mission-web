package vn.viettel.okrkpi.dossier.infrastructure.client;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * {@link RenderRequests} qua REST của SVC-11: {@code POST /api/v1/dossiers/render-jobs} ({@code
 * createDossierRenderJob}, 202 kèm {@code Job}) và {@code GET /api/v1/jobs/{id}}. MOD-06 gọi bằng
 * token dịch vụ (client credentials) và tenant đích ở {@code X-Tenant-Id} như lời gọi workflow của
 * saga chốt kỳ; vì cùng một người gọi nên SVC-11 cho đọc lại trạng thái lệnh (chỉ người tạo lệnh
 * được xem tác vụ).
 */
public class HttpRenderRequests implements RenderRequests {

  public static final String INSTANCE = "dossier-docgen";
  static final String RENDER_JOBS = "/api/v1/dossiers/render-jobs";
  static final String JOB = "/api/v1/jobs/{id}";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final Set<String> FINISHED = Set.of("HOAN_TAT", "THAT_BAI");

  private final ResilientClient client;
  private final Supplier<String> token;

  public HttpRenderRequests(ResilientClient client, Supplier<String> token) {
    this.client = Objects.requireNonNull(client, "client");
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public UUID request(TenantId tenant, UUID packageId, String idempotencyKey) {
    try {
      HttpHeaders h = headers(tenant);
      h.setContentType(MediaType.APPLICATION_JSON);
      h.set(ResilientClient.IDEMPOTENCY_KEY, idempotencyKey);
      Map<?, ?> job =
          client.exchange(
              HttpMethod.POST,
              RENDER_JOBS,
              Map.of("packageId", packageId.toString()),
              h,
              Map.class);
      Object id = job == null ? null : job.get("id");
      if (id == null) {
        throw new RenderUnavailableException("SVC-11 không trả mã lệnh sinh", null);
      }
      return UUID.fromString(id.toString());
    } catch (RenderUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new RenderUnavailableException(e.getMessage(), e);
    }
  }

  @Override
  public Optional<RequestState> state(TenantId tenant, UUID requestId) {
    Map<?, ?> job;
    try {
      job = client.exchange(HttpMethod.GET, JOB, null, headers(tenant), Map.class, requestId);
    } catch (RuntimeException e) {
      // 404 (lệnh không còn), 5xx hay mất kết nối: lần theo dõi sau đọc lại.
      return Optional.empty();
    }
    if (job == null || job.get("status") == null) {
      return Optional.empty();
    }
    return Optional.of(
        new RequestState(
            FINISHED.contains(job.get("status").toString()), time(job.get("updatedAt"))));
  }

  private HttpHeaders headers(TenantId tenant) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token.get());
    h.set(TENANT_HEADER, tenant.value().toString());
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    return h;
  }

  private static Instant time(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.toString()).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
