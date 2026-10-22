package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
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
    MySignProperties.Storage storage = properties.storage();
    var builder =
        S3Client.builder()
            .region(Region.of(storage.region()))
            .httpClient(UrlConnectionHttpClient.create())
            .forcePathStyle(true);
    if (storage.endpoint() != null && !storage.endpoint().isBlank()) {
      builder.endpointOverride(URI.create(storage.endpoint()));
    }
    if (storage.accessKey() != null && !storage.accessKey().isBlank()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())));
    }
    return builder.build();
  }

  @Bean
  MySignProvider mySignProvider(
      MySignProperties properties,
      ResilientClientFactory factory,
      S3Client s3,
      ObjectProvider<ObjectMapper> json,
      Clock clock) {
    CscClient csc =
        new CscClient(
            factory.create(INSTANCE, properties.baseUrl()),
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
