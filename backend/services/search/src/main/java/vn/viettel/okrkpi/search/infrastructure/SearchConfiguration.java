package vn.viettel.okrkpi.search.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.search.application.IndexingService;
import vn.viettel.okrkpi.search.application.RebuildService;
import vn.viettel.okrkpi.shared.inbox.ApicurioPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;

/** Nối các thành phần của indexer SVC-16. */
@Configuration
@EnableKafka
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {

  static final List<String> SOURCE_TOPICS =
      List.of(
          EventTopics.EVT_04_NHIEM_VU_DA_GIAO,
          EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI,
          EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN);

  @Bean
  OpenSearchIndex openSearchIndex(SearchProperties p, ObjectMapper json) {
    OpenSearchIndex index =
        new OpenSearchIndex(
            new OpenSearchHttp(
                p.opensearchUrl(),
                p.opensearchUsername(),
                p.opensearchPassword(),
                p.timeout(),
                json),
            json,
            p);
    index.installTemplate();
    return index;
  }

  @Bean
  IndexingService indexingService(OpenSearchIndex index, SearchProperties p, MeterRegistry meters) {
    return new IndexingService(index, p.defaultModel(), meters, Clock.systemUTC());
  }

  @Bean
  InboxPayloadDeserializer searchPayloadDeserializer(SearchProperties p) {
    return "avro-binary".equals(p.payloadFormat())
        ? new AvroBinaryDeserializer()
        : new ApicurioPayloadDeserializer(p.registryUrl(), Map.of());
  }

  @Bean
  EventDecoder eventDecoder(InboxPayloadDeserializer payloads) {
    return new EventDecoder(payloads);
  }

  @Bean
  InboxRetryDlqPublisher searchDlqPublisher(KafkaProperties kafka) {
    Map<String, Object> cfg = new HashMap<>(kafka.buildProducerProperties(null));
    cfg.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
    return new InboxRetryDlqPublisher(
        new KafkaTemplate<>(
            new DefaultKafkaProducerFactory<>(
                cfg, new StringSerializer(), new ByteArraySerializer())));
  }

  @Bean
  KafkaIndexListener kafkaIndexListener(
      IndexingService service,
      EventDecoder decoder,
      InboxRetryDlqPublisher dlq,
      SearchProperties p) {
    return new KafkaIndexListener(service, decoder, dlq, p.consumerGroup());
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, byte[]> searchBatchFactory(
      KafkaProperties kafka, SearchProperties p) {
    ConcurrentKafkaListenerContainerFactory<String, byte[]> f = factory(kafka);
    f.setBatchListener(true);
    f.setConcurrency(p.concurrency());
    return f;
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, byte[]> searchRecordFactory(
      KafkaProperties kafka) {
    return factory(kafka);
  }

  private static ConcurrentKafkaListenerContainerFactory<String, byte[]> factory(
      KafkaProperties kafka) {
    ConcurrentKafkaListenerContainerFactory<String, byte[]> f =
        new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(
            consumerConfig(kafka), new StringDeserializer(), new ByteArrayDeserializer()));
    // Lỗi tạm thời của OpenSearch: thử lại không giới hạn với backoff tới 30 giây; chỉ mục là mô
    // hình đọc, chờ cụm
    // hồi phục tốt hơn bỏ sự kiện.
    ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
    backOff.setMaxInterval(30_000);
    f.setCommonErrorHandler(new DefaultErrorHandler(backOff));
    return f;
  }

  private static Map<String, Object> consumerConfig(KafkaProperties kafka) {
    Map<String, Object> cfg = new HashMap<>(kafka.buildConsumerProperties(null));
    cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    cfg.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return cfg;
  }

  @Bean
  KafkaReplaySource kafkaReplaySource(KafkaProperties kafka, EventDecoder decoder) {
    return new KafkaReplaySource(
        () -> {
          Map<String, Object> cfg = consumerConfig(kafka);
          cfg.remove(ConsumerConfig.GROUP_ID_CONFIG);
          cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
          return new KafkaConsumer<>(cfg, new StringDeserializer(), new ByteArrayDeserializer());
        },
        SOURCE_TOPICS,
        decoder);
  }

  @Bean
  RebuildService rebuildService(OpenSearchIndex index, KafkaReplaySource source) {
    return new RebuildService(index, source);
  }
}
