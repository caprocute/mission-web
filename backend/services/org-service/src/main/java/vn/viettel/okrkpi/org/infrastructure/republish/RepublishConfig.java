package vn.viettel.okrkpi.org.infrastructure.republish;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.org.application.port.BootstrapPublisher;
import vn.viettel.okrkpi.org.application.port.RepublishSource;
import vn.viettel.okrkpi.org.application.republish.OrgRepublishService;

/** Hồ sơ {@code republish}: producer Kafka idempotent, bộ mã hoá Apicurio và dịch vụ phát lại. */
@Configuration(proxyBeanMethods = false)
@Profile("republish")
@EnableConfigurationProperties(RepublishProperties.class)
public class RepublishConfig {

  @Bean
  @ConditionalOnMissingBean
  PayloadEncoder republishPayloadEncoder(RepublishProperties props) {
    return new ApicurioPayloadEncoder(props.registryUrl(), props.registry());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  Producer<String, byte[]> republishProducer(RepublishProperties props) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServers());
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    config.put(ProducerConfig.LINGER_MS_CONFIG, 20);
    config.put(ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024);
    config.put(ProducerConfig.CLIENT_ID_CONFIG, "org-republish");
    config.putAll(props.kafka());
    return new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
  }

  @Bean
  BootstrapPublisher bootstrapPublisher(Producer<String, byte[]> producer, PayloadEncoder encoder, Clock clock) {
    return new KafkaBootstrapPublisher(producer, encoder, clock);
  }

  @Bean
  OrgRepublishService orgRepublishService(RepublishSource source, BootstrapPublisher publisher,
      PlatformTransactionManager tm, Clock clock) {
    return new OrgRepublishService(source, publisher, tm, clock);
  }
}
