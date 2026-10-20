package vn.viettel.okrkpi.shared.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration chịu lỗi (ADR-24): {@link ResilientClientFactory} dùng các registry
 * Resilience4j mà {@code resilience4j-spring-boot3} dựng từ cấu hình chuẩn của TL-01, và bộ xử lý
 * lỗi cache "bỏ qua khi lỗi".
 */
@AutoConfiguration(
    afterName = {
      "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration",
      "io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration",
      "io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration",
      "io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration",
      "org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration"
    })
@ConditionalOnClass({CircuitBreakerRegistry.class, RestClient.class})
public class ResilienceAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({
    CircuitBreakerRegistry.class,
    RetryRegistry.class,
    TimeLimiterRegistry.class,
    BulkheadRegistry.class
  })
  public ResilientClientFactory resilientClientFactory(
      ObjectProvider<RestClient.Builder> builders,
      CircuitBreakerRegistry circuitBreakers,
      RetryRegistry retries,
      TimeLimiterRegistry timeLimiters,
      BulkheadRegistry bulkheads) {
    return new ResilientClientFactory(builders, circuitBreakers, retries, timeLimiters, bulkheads);
  }

  /**
   * Valkey lỗi thì bỏ qua bộ đệm; chỉ áp khi service chưa tự khai báo {@link CachingConfigurer}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(CacheErrorHandler.class)
  static class LenientCacheConfiguration {

    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    CachingConfigurer lenientCachingConfigurer() {
      return new CachingConfigurer() {
        @Override
        public CacheErrorHandler errorHandler() {
          return new LenientCacheErrorHandler();
        }
      };
    }
  }
}
