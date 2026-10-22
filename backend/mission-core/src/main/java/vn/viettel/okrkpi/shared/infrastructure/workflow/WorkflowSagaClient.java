package vn.viettel.okrkpi.shared.infrastructure.workflow;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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

  /** Khởi động saga {@code CHOT_KY}; trả mã saga. */
  public UUID startPeriodClose(
      UUID periodId, UUID lockOrderId, UUID requestedBy, String idempotencyKey) {
    try {
      Map<String, Object> job =
          http.post()
              .uri("/api/v1/workflow/sagas/period-close")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
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

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        "workflow-service",
        DependencyUnavailableException.Reason.IO_ERROR,
        Duration.ofSeconds(5),
        e);
  }
}
