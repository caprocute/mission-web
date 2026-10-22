package vn.viettel.okrkpi.evidence.infrastructure.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.TenantBucketService;

/**
 * Kết nối Ceph RGW (DV-08-01). Khoá truy cập đến từ biến môi trường do OpenBao cấp; để trống thì
 * dùng chuỗi nguồn khoá mặc định của SDK.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

  @Bean(destroyMethod = "close")
  S3Client evidenceS3Client(
      @Value("${okrkpi.evidence.storage.endpoint}") String endpoint,
      @Value("${okrkpi.evidence.storage.region}") String region,
      @Value("${okrkpi.evidence.storage.access-key:}") String accessKey,
      @Value("${okrkpi.evidence.storage.secret-key:}") String secretKey) {
    AwsCredentialsProvider credentials =
        accessKey.isBlank()
            ? DefaultCredentialsProvider.builder().build()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(credentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  @Bean
  ObjectStorage objectStorage(S3Client evidenceS3Client) {
    return new S3ObjectStorage(evidenceS3Client);
  }

  @Bean
  TenantBucketService tenantBucketService(
      ObjectStorage objectStorage,
      @Value("${okrkpi.evidence.storage.cell}") String cell,
      @Value("${okrkpi.evidence.storage.lock-days}") int lockDays) {
    return new TenantBucketService(objectStorage, cell, lockDays);
  }
}
