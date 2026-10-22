package vn.viettel.okrkpi.iam.sync;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Lỗi tạm thời được thử lại trong tiến trình với giãn cách tăng dần ({@code mission.user-sync.retry.*}), hết lượt
 * thì sang thư chết {@code <topic>.dlq.iam-user-sync} theo quy ước NEN-012 ({@code InboxRetryDlqPublisher}); lỗi vĩnh
 * viễn vào thư chết ngay. Thông điệp trong thư chết giữ nguyên thân và header, cộng header lỗi của Spring Kafka.
 */
@Configuration(proxyBeanMethods = false)
public class UserSyncConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  KeycloakDirectory keycloakDirectory(KeycloakProperties props, Clock clock) {
    CircuitBreaker breaker = CircuitBreaker.of("keycloak-admin", CircuitBreakerConfig.custom()
        .failureRateThreshold(50).slidingWindowSize(20).minimumNumberOfCalls(10)
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .ignoreExceptions(org.springframework.web.client.HttpClientErrorException.class).build());
    return new KeycloakDirectory(props, breaker, clock);
  }

  @Bean
  PersonSyncService personSyncService(Directory directory) {
    return new PersonSyncService(directory);
  }

  @Bean
  PersonListener personListener(PersonSyncService service, MeterRegistry meters, Clock clock) {
    return new PersonListener(service, meters, clock);
  }

  @Bean
  UnitSyncService unitSyncService(Directory directory) {
    return new UnitSyncService(directory);
  }

  @Bean
  UnitListener unitListener(UnitSyncService service, MeterRegistry meters, Clock clock) {
    return new UnitListener(service, meters, clock);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, byte[]> userSyncListenerFactory(
      ConsumerFactory<String, byte[]> consumers, KafkaTemplate<String, byte[]> template, MeterRegistry meters,
      @Value("${mission.user-sync.retry.initial:500ms}") Duration initial,
      @Value("${mission.user-sync.retry.max-elapsed:20s}") Duration maxElapsed) {
    return factory(consumers, template, meters, PersonListener.CONSUMER, "iam_user_sync_dlq_total", initial,
        maxElapsed);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, byte[]> unitSyncListenerFactory(
      ConsumerFactory<String, byte[]> consumers, KafkaTemplate<String, byte[]> template, MeterRegistry meters,
      @Value("${mission.user-sync.retry.initial:500ms}") Duration initial,
      @Value("${mission.user-sync.retry.max-elapsed:20s}") Duration maxElapsed) {
    return factory(consumers, template, meters, UnitListener.CONSUMER, "iam_unit_sync_dlq_total", initial,
        maxElapsed);
  }

  private static ConcurrentKafkaListenerContainerFactory<String, byte[]> factory(
      ConsumerFactory<String, byte[]> consumers, KafkaTemplate<String, byte[]> template, MeterRegistry meters,
      String consumer, String dlqMetric, Duration initial, Duration maxElapsed) {
    Counter dlq = Counter.builder(dlqMetric).description("Thông điệp vào thư chết").register(meters);
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
      dlq.increment();
      return new TopicPartition(record.topic() + ".dlq." + consumer, -1);
    });
    ExponentialBackOff backOff = new ExponentialBackOff(initial.toMillis(), 2.0);
    backOff.setMaxInterval(Duration.ofSeconds(10).toMillis());
    backOff.setMaxElapsedTime(maxElapsed.toMillis());
    DefaultErrorHandler errors = new DefaultErrorHandler(recoverer, backOff);
    errors.addNotRetryableExceptions(Directory.PermanentException.class);
    ConcurrentKafkaListenerContainerFactory<String, byte[]> f = new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(consumers);
    f.setCommonErrorHandler(errors);
    return f;
  }
}
