package vn.viettel.okrkpi.shared.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Bọc {@link RestClient} cho lời gọi ra ngoài theo TL-01 "Cấu hình Resilience4j chuẩn": bulkhead
 * ngoài cùng, rồi time limiter, circuit breaker, retry trong cùng; retry chỉ cho thao tác
 * idempotent (GET, HEAD, hoặc có {@code Idempotency-Key}).
 *
 * <p>Lỗi I/O của {@code RestClient} ({@link ResourceAccessException}) được mở ra thành {@link
 * IOException} bên trong chuỗi, để {@code recordExceptions}/{@code retryExceptions} của YAML chuẩn
 * có hiệu lực. Ra khỏi chuỗi, mạch mở, bulkhead đầy, quá thời gian và lỗi I/O thành {@link
 * DependencyUnavailableException} (503 kèm {@code Retry-After}); lỗi HTTP khác giữ nguyên.
 */
public final class ResilientClient {

  /** Header khoá chống ghi trùng (baseline mục 13). */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private static final Set<HttpMethod> IDEMPOTENT_WITHOUT_KEY =
      Set.of(HttpMethod.GET, HttpMethod.HEAD);
  private static final Duration SHORT_RETRY_AFTER = Duration.ofSeconds(1);

  private final String name;
  private final RestClient client;
  private final Bulkhead bulkhead;
  private final TimeLimiter timeLimiter;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final ExecutorService executor;

  ResilientClient(
      String name,
      RestClient client,
      Bulkhead bulkhead,
      TimeLimiter timeLimiter,
      CircuitBreaker circuitBreaker,
      Retry retry,
      ExecutorService executor) {
    this.name = Objects.requireNonNull(name, "name");
    this.client = Objects.requireNonNull(client, "client");
    this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
    this.timeLimiter = Objects.requireNonNull(timeLimiter, "timeLimiter");
    this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
    this.retry = Objects.requireNonNull(retry, "retry");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public String name() {
    return name;
  }

  public CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  /** GET: được thử lại. */
  public <T> T get(String uriTemplate, Class<T> responseType, Object... uriVariables) {
    return exchange(HttpMethod.GET, uriTemplate, null, null, responseType, uriVariables);
  }

  /**
   * POST: chỉ được thử lại khi có {@code idempotencyKey}.
   *
   * @param idempotencyKey khoá chống ghi trùng, {@code null} nếu thao tác không idempotent
   */
  public <T> T post(
      String uriTemplate,
      Object body,
      String idempotencyKey,
      Class<T> responseType,
      Object... uriVariables) {
    HttpHeaders headers = new HttpHeaders();
    if (idempotencyKey != null) {
      headers.set(IDEMPOTENCY_KEY, idempotencyKey);
    }
    return exchange(HttpMethod.POST, uriTemplate, body, headers, responseType, uriVariables);
  }

  /** Lời gọi tổng quát. */
  public <T> T exchange(
      HttpMethod method,
      String uriTemplate,
      Object body,
      HttpHeaders headers,
      Class<T> responseType,
      Object... uriVariables) {
    HttpHeaders h = headers == null ? new HttpHeaders() : headers;
    Callable<T> call = () -> invoke(method, uriTemplate, body, h, responseType, uriVariables);
    Callable<T> withRetry = isRetryable(method, h) ? Retry.decorateCallable(retry, call) : call;
    Callable<T> withBreaker = CircuitBreaker.decorateCallable(circuitBreaker, withRetry);
    Callable<T> withTimeLimit =
        () -> timeLimiter.executeFutureSupplier(() -> executor.submit(propagate(withBreaker)));
    Callable<T> guarded = Bulkhead.decorateCallable(bulkhead, withTimeLimit);
    try {
      return guarded.call();
    } catch (CallNotPermittedException e) {
      throw unavailable(DependencyUnavailableException.Reason.CIRCUIT_OPEN, openWait(), e);
    } catch (BulkheadFullException e) {
      throw unavailable(DependencyUnavailableException.Reason.BULKHEAD_FULL, SHORT_RETRY_AFTER, e);
    } catch (TimeoutException e) {
      throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, SHORT_RETRY_AFTER, e);
    } catch (IOException e) {
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, SHORT_RETRY_AFTER, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(DependencyUnavailableException.Reason.TIMEOUT, SHORT_RETRY_AFTER, e);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw unavailable(DependencyUnavailableException.Reason.IO_ERROR, SHORT_RETRY_AFTER, e);
    }
  }

  /** Đúng khi thao tác được phép thử lại: GET, HEAD hoặc có {@code Idempotency-Key}. */
  static boolean isRetryable(HttpMethod method, HttpHeaders headers) {
    if (IDEMPOTENT_WITHOUT_KEY.contains(method)) {
      return true;
    }
    String key = headers.getFirst(IDEMPOTENCY_KEY);
    return key != null && !key.isBlank();
  }

  private <T> T invoke(
      HttpMethod method,
      String uriTemplate,
      Object body,
      HttpHeaders headers,
      Class<T> responseType,
      Object... uriVariables)
      throws IOException {
    try {
      RestClient.RequestBodySpec spec =
          client.method(method).uri(uriTemplate, uriVariables).headers(x -> x.addAll(headers));
      if (body != null) {
        spec.body(body);
      }
      return spec.retrieve().body(responseType);
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof IOException io) {
        throw io;
      }
      throw e;
    }
  }

  /**
   * Mang ngữ cảnh OpenTelemetry và MDC (trace_id, tenant_id) sang luồng ảo chạy lời gọi, để nhật ký
   * và span của lời gọi ra ngoài vẫn gắn với yêu cầu.
   */
  static <T> Callable<T> propagate(Callable<T> task) {
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    Callable<T> withMdc =
        () -> {
          Map<String, String> previous = MDC.getCopyOfContextMap();
          if (mdc == null) {
            MDC.clear();
          } else {
            MDC.setContextMap(mdc);
          }
          try {
            return task.call();
          } finally {
            if (previous == null) {
              MDC.clear();
            } else {
              MDC.setContextMap(previous);
            }
          }
        };
    return Context.current().wrap(withMdc);
  }

  private Duration openWait() {
    Long ms =
        circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
    return ms == null ? SHORT_RETRY_AFTER : Duration.ofMillis(ms);
  }

  private DependencyUnavailableException unavailable(
      DependencyUnavailableException.Reason reason, Duration retryAfter, Throwable cause) {
    return new DependencyUnavailableException(name, reason, retryAfter, cause);
  }
}
