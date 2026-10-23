package vn.viettel.okrkpi.shared.infrastructure.workflow;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client {@code POST /api/v1/workflow/sagas/period-close} ({@code startPeriodCloseSaga} của {@code
 * platform.yaml}, SVC-06). Mã job trả về là mã saga. Các mô-đun nghiệp vụ không gọi HTTP trực tiếp
 * (ArchUnit), nên client nằm ở {@code shared.infrastructure} như client Keycloak.
 */
public class WorkflowSagaClient {

  /** SVC-06 trả 409 {@code WORKFLOW-409-SAGA-EXISTS}. */
  public static final class SagaExists extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SagaExists(Throwable cause) {
      super("Saga đã có", cause);
    }
  }

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final Supplier<String> token;

  public WorkflowSagaClient(RestClient.Builder builder, String baseUrl, Supplier<String> token) {
    this.http = builder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
    this.token = Objects.requireNonNull(token, "token");
  }

  /** Header tenant đích của lời gọi dịch vụ tới dịch vụ (wiki trang 14 mục 42). */
  public static final String TENANT_HEADER = "X-Tenant-Id";

  /**
   * Khởi động saga {@code CHOT_KY}; trả mã saga. Token dịch vụ không mang claim tenant nên tenant
   * đích gửi trong {@link #TENANT_HEADER}.
   */
  public UUID startPeriodClose(
      TenantId tenantId, UUID periodId, UUID lockOrderId, UUID requestedBy, String idempotencyKey) {
    Objects.requireNonNull(tenantId, "tenantId");
    try {
      Map<String, Object> job =
          http.post()
              .uri("/api/v1/workflow/sagas/period-close")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
              .header(TENANT_HEADER, tenantId.value().toString())
              .header("Idempotency-Key", idempotencyKey)
              .body(
                  Map.of(
                      "periodId", periodId,
                      "lockOrderId", lockOrderId,
                      "unitIds", java.util.List.of(),
                      "requestedBy", requestedBy))
              .retrieve()
              .body(MAP);
      if (job == null || !(job.get("id") instanceof String id)) {
        throw new RestClientException("workflow-service không trả mã job");
      }
      return UUID.fromString(id);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
        throw new SagaExists(e);
      }
      throw unavailable(e);
    } catch (RestClientException | IllegalArgumentException e) {
      throw unavailable(e);
    }
  }

  /**
   * Kết quả {@link #controlPeriodClose}: {@code accepted} khi SVC-06 trả 202; ngược lại là lỗi
   * nghiệp vụ (403, 404, 409, 422, 400) kèm mã HTTP và {@code code} của Problem nếu có.
   */
  public record ControlOutcome(boolean accepted, int status, String code) {}

  /** Mã HTTP coi là lỗi nghiệp vụ của lệnh điều khiển, không thử lại (wiki trang 14, DV-06-07). */
  private static final Set<Integer> REJECTED = Set.of(400, 403, 404, 409, 422);

  /**
   * Chuyển một dòng hộp lệnh {@code config.period_lock_command} tới saga {@code CHOT_KY} ({@code
   * controlPeriodCloseSaga}: {@code POST /api/v1/workflow/sagas/period-close/{sagaId}/commands}),
   * {@code Idempotency-Key} là mã lệnh. Lỗi mạng, 5xx, 401 và 429 ném {@link
   * DependencyUnavailableException} để bên gọi thử lại với cùng khoá.
   */
  public ControlOutcome controlPeriodClose(
      TenantId tenantId, UUID sagaId, UUID commandId, Map<String, Object> body) {
    Objects.requireNonNull(tenantId, "tenantId");
    try {
      http.post()
          .uri("/api/v1/workflow/sagas/period-close/{sagaId}/commands", sagaId)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
          .header(TENANT_HEADER, tenantId.value().toString())
          .header("Idempotency-Key", commandId.toString())
          .body(body)
          .retrieve()
          .toBodilessEntity();
      return new ControlOutcome(true, 202, null);
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      if (!REJECTED.contains(status)) {
        throw unavailable(e);
      }
      return new ControlOutcome(false, status, problemCode(e));
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private static String problemCode(HttpClientErrorException e) {
    try {
      Map<String, Object> problem = e.getResponseBodyAs(MAP);
      return problem != null && problem.get("code") instanceof String c ? c : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        "workflow-service",
        DependencyUnavailableException.Reason.IO_ERROR,
        Duration.ofSeconds(5),
        e);
  }
}
