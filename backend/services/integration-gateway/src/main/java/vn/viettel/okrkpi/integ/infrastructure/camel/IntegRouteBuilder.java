package vn.viettel.okrkpi.integ.infrastructure.camel;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.DeadLetterChannelBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.ProcessorDefinition;

/**
 * Khung route chuẩn của mọi bộ kết nối: thư chết {@value #DEAD_LETTER} sau số lần thử lại luỹ thừa;
 * circuit breaker Resilience4j kèm bulkhead riêng. Nhật ký thử lại và thư chết không in nội dung
 * thông điệp.
 */
public abstract class IntegRouteBuilder extends RouteBuilder {

  public static final String DEAD_LETTER = "direct:integ-dead-letter";

  protected final RouteSettings settings;

  protected IntegRouteBuilder(RouteSettings settings) {
    this.settings = settings;
  }

  /** Bộ xử lý lỗi chuẩn đặt ở đầu {@code configure()}. */
  protected DeadLetterChannelBuilder standardDeadLetter() {
    DeadLetterChannelBuilder dlc = deadLetterChannel(DEAD_LETTER);
    dlc.maximumRedeliveries(settings.maxRedeliveries())
        .redeliveryDelay(settings.redeliveryDelay().toMillis())
        .backOffMultiplier(settings.backOffMultiplier())
        .useExponentialBackOff()
        .retryAttemptedLogLevel(LoggingLevel.WARN)
        .logExhaustedMessageBody(false)
        .logExhaustedMessageHistory(false)
        .logRetryStackTrace(false)
        .logStackTrace(false);
    return dlc;
  }

  /**
   * Circuit breaker Resilience4j của bộ kết nối, kèm bulkhead {@code maxConcurrentCalls}; trả định
   * nghĩa để route viết bước gọi đối tác rồi {@code onFallback()}.
   */
  protected CircuitBreakerDefinition breaker(
      ProcessorDefinition<?> route, String id, int maxConcurrentCalls) {
    return route
        .circuitBreaker()
        .id(id)
        .resilience4jConfiguration()
        .failureRateThreshold(settings.failureRateThreshold())
        .slidingWindowSize(settings.slidingWindowSize())
        .minimumNumberOfCalls(settings.slidingWindowSize())
        .waitDurationInOpenState((int) settings.waitInOpenState().toSeconds())
        .timeoutEnabled(true)
        .timeoutDuration((int) settings.callTimeout().toMillis())
        .bulkheadEnabled(true)
        .bulkheadMaxConcurrentCalls(maxConcurrentCalls)
        .end();
  }
}
