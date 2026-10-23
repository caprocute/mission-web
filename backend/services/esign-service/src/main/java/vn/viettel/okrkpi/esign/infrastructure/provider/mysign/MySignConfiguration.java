package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.esign.infrastructure.provider.csc.CscHttp;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Lắp {@link MySignProvider} khi {@code okrkpi.esign.mysign.enabled=true} (DV-10-02). Lời gọi tới
 * MySign dùng instance Resilience4j {@code esign-mysign} của TL-01; máy chủ dấu thời gian dùng
 * instance {@code esign-tsa} (cấu hình {@code default}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MySignProperties.class)
@ConditionalOnProperty(prefix = "okrkpi.esign.mysign", name = "enabled", havingValue = "true")
public class MySignConfiguration {

  static final String INSTANCE = "esign-mysign";
  static final String TSA_INSTANCE = "esign-tsa";

  @Bean(destroyMethod = "close")
  S3Client esignS3(MySignProperties properties) {
    return S3ObjectStorage.client(properties.storage());
  }

  @Bean
  MySignProvider mySignProvider(
      MySignProperties properties,
      ResilientClientFactory factory,
      S3Client s3,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<RestClient.Builder> builders,
      @Value("${okrkpi.esign.csc.read-timeout:10s}") Duration readTimeout,
      Clock clock) {
    CscClient csc =
        new CscClient(
            CscHttp.client(factory, builders, INSTANCE, properties.baseUrl(), readTimeout),
            factory.create(INSTANCE, properties.tokenUrl()),
            properties,
            clock);
    TimestampClient tsa =
        properties.tsaUrl() == null || properties.tsaUrl().isBlank()
            ? null
            : new TimestampClient(factory.create(TSA_INSTANCE, properties.tsaUrl()));
    return new MySignProvider(
        csc,
        new S3ObjectStorage(s3, properties.storage().bucket()),
        tsa,
        properties,
        json.getIfAvailable(ObjectMapper::new),
        clock);
  }
}
