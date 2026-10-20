package vn.viettel.okrkpi.shared.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

/**
 * Tạo {@link ResilientClient} theo tên instance của Resilience4j ({@code esign-mysign}, {@code
 * vneid}, {@code cbccvc}, {@code docgen}…); instance chưa khai báo dùng cấu hình {@code default}.
 * {@code RestClient} lấy từ {@code RestClient.Builder} của Spring Boot, đã mang timeout kết nối 500
 * ms và đọc 3 s ({@code spring.http.client.*}).
 *
 * <p>Lời gọi được time limiter giám sát chạy trên luồng ảo; số lời gọi đồng thời bị chặn bởi
 * bulkhead semaphore đặt trước (TL-01 "Cô lập tài nguyên theo phụ thuộc").
 */
public class ResilientClientFactory implements DisposableBean {

  private final ObjectProvider<RestClient.Builder> builders;
  private final CircuitBreakerRegistry circuitBreakers;
  private final RetryRegistry retries;
  private final TimeLimiterRegistry timeLimiters;
  private final BulkheadRegistry bulkheads;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ResilientClientFactory(
      ObjectProvider<RestClient.Builder> builders,
      CircuitBreakerRegistry circuitBreakers,
      RetryRegistry retries,
      TimeLimiterRegistry timeLimiters,
      BulkheadRegistry bulkheads) {
    this.builders = builders;
    this.circuitBreakers = circuitBreakers;
    this.retries = retries;
    this.timeLimiters = timeLimiters;
    this.bulkheads = bulkheads;
  }

  /** Client tới {@code baseUrl} với các instance Resilience4j cùng tên {@code instance}. */
  public ResilientClient create(String instance, String baseUrl) {
    RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
    return create(instance, builder.baseUrl(baseUrl).build());
  }

  /** Bọc một {@link RestClient} sẵn có. */
  public ResilientClient create(String instance, RestClient client) {
    Objects.requireNonNull(instance, "instance");
    return new ResilientClient(
        instance,
        client,
        bulkheads.bulkhead(instance),
        timeLimiters.timeLimiter(instance),
        circuitBreakers.circuitBreaker(instance),
        retries.retry(instance),
        executor);
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
