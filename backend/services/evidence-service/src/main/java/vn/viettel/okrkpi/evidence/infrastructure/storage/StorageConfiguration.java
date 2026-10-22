package vn.viettel.okrkpi.evidence.infrastructure.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.TenantBucketService;

/**
 * Kết nối Ceph RGW (DV-08-01). Khoá truy cập đến từ biến môi trường do OpenBao cấp; để trống thì
 * dùng chuỗi nguồn khoá mặc định của SDK. Tổng kiểm tra CRC của SDK chỉ gửi khi S3 bắt buộc, vì RGW
 * chưa nhận đủ kiểu tổng kiểm tra mới của AWS.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

  @Bean
  AwsCredentialsProvider evidenceS3Credentials(
      @Value("${okrkpi.evidence.storage.access-key:}") String accessKey,
      @Value("${okrkpi.evidence.storage.secret-key:}") String secretKey) {
    return accessKey.isBlank()
        ? DefaultCredentialsProvider.builder().build()
        : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
  }

  @Bean(destroyMethod = "close")
  S3Client evidenceS3Client(
      @Value("${okrkpi.evidence.storage.endpoint}") String endpoint,
      @Value("${okrkpi.evidence.storage.region}") String region,
      AwsCredentialsProvider evidenceS3Credentials) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(evidenceS3Credentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  /** URL tải ký bằng địa chỉ công khai của RGW (qua APISIX), mặc định trùng địa chỉ nội bộ. */
  @Bean(destroyMethod = "close")
  S3Presigner evidenceS3Presigner(
      @Value("${okrkpi.evidence.storage.public-endpoint:${okrkpi.evidence.storage.endpoint}}")
          String endpoint,
      @Value("${okrkpi.evidence.storage.region}") String region,
      AwsCredentialsProvider evidenceS3Credentials) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(evidenceS3Credentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  @Bean
  ObjectStorage objectStorage(S3Client evidenceS3Client, S3Presigner evidenceS3Presigner) {
    return new S3ObjectStorage(evidenceS3Client, evidenceS3Presigner);
  }

  @Bean
  TenantBucketService tenantBucketService(
      ObjectStorage objectStorage,
      @Value("${okrkpi.evidence.storage.cell}") String cell,
      @Value("${okrkpi.evidence.storage.lock-days}") int lockDays) {
    return new TenantBucketService(objectStorage, cell, lockDays);
  }
}
