package vn.viettel.okrkpi.esign.infrastructure.tsa;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.S3ObjectStorage;

/**
 * Lắp job gia hạn dấu thời gian lưu trữ khi {@code okrkpi.esign.archive-timestamp.enabled=true};
 * cần client dấu thời gian và kho đối tượng của esign.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ArchiveTimestampConfiguration.Properties.class)
@ConditionalOnProperty(
    prefix = "okrkpi.esign.archive-timestamp",
    name = "enabled",
    havingValue = "true")
public class ArchiveTimestampConfiguration {

  /**
   * Cấu hình job.
   *
   * @param renewBefore gia hạn khi chứng thư TSA hết hạn trong khoảng này (mặc định 400 ngày, đủ
   *     cho một chu kỳ chạy hằng năm)
   * @param batchSize số tài liệu mỗi trang quét
   * @param bucket bucket chứa tệp đã ký
   */
  @ConfigurationProperties(prefix = "okrkpi.esign.archive-timestamp")
  public record Properties(
      boolean enabled, Duration renewBefore, Integer batchSize, String bucket) {
    public Properties {
      renewBefore = renewBefore == null ? Duration.ofDays(400) : renewBefore;
      batchSize = batchSize == null || batchSize < 1 ? 200 : batchSize;
      bucket = bucket == null || bucket.isBlank() ? "dossier" : bucket;
    }
  }

  @Bean
  ArchiveTimestampRenewalJob archiveTimestampRenewalJob(
      Properties properties,
      JdbcClient jdbc,
      ObjectProvider<S3Client> s3,
      ObjectProvider<TsaClient> tsa,
      PlatformTransactionManager transactions,
      Clock clock) {
    S3Client client = s3.getIfAvailable();
    TsaClient authority = tsa.getIfAvailable();
    if (client == null || authority == null) {
      throw new IllegalStateException(
          "Job gia hạn dấu thời gian cần kho đối tượng và máy chủ dấu thời gian");
    }
    return new ArchiveTimestampRenewalJob(
        jdbc,
        new S3ObjectStorage(client, properties.bucket()),
        authority,
        new TransactionTemplate(transactions),
        clock,
        properties.renewBefore(),
        properties.batchSize());
  }
}
