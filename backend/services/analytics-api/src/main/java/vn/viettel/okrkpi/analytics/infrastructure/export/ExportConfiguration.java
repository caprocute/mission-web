package vn.viettel.okrkpi.analytics.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
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
import vn.viettel.okrkpi.analytics.application.DashboardService;
import vn.viettel.okrkpi.analytics.application.ExportPorts;
import vn.viettel.okrkpi.analytics.application.ExportService;
import vn.viettel.okrkpi.analytics.application.QueryService;
import vn.viettel.okrkpi.analytics.application.ScopeService;
import vn.viettel.okrkpi.analytics.application.SemanticModelService;
import vn.viettel.okrkpi.analytics.application.TraceService;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.cache.TenantCache;
import vn.viettel.okrkpi.shared.job.JobSource;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * DV-15-06 · tác vụ xuất: kho đối tượng S3 của cell (như evidence-service: path-style, tổng kiểm
 * tra CRC chỉ khi S3 bắt buộc), Gotenberg dựng PDF, Valkey giữ trạng thái, luồng nền có giới hạn.
 * Khoá truy cập đến từ biến môi trường; để trống thì dùng chuỗi nguồn khoá mặc định của SDK.
 */
@Configuration(proxyBeanMethods = false)
public class ExportConfiguration {

  @Bean
  AwsCredentialsProvider analyticsExportCredentials(
      @Value("${mission.analytics.export.access-key:}") String accessKey,
      @Value("${mission.analytics.export.secret-key:}") String secretKey) {
    return accessKey.isBlank()
        ? DefaultCredentialsProvider.builder().build()
        : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
  }

  @Bean(destroyMethod = "close")
  S3Client analyticsExportS3(
      @Value("${mission.analytics.export.endpoint}") String endpoint,
      @Value("${mission.analytics.export.region:us-east-1}") String region,
      AwsCredentialsProvider analyticsExportCredentials) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(analyticsExportCredentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  /** URL tải ký bằng địa chỉ công khai của RGW (qua APISIX), mặc định trùng địa chỉ nội bộ. */
  @Bean(destroyMethod = "close")
  S3Presigner analyticsExportPresigner(
      @Value("${mission.analytics.export.public-endpoint:${mission.analytics.export.endpoint}}")
          String endpoint,
      @Value("${mission.analytics.export.region:us-east-1}") String region,
      AwsCredentialsProvider analyticsExportCredentials) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(analyticsExportCredentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  @Bean
  ExportPorts.Store analyticsExportStore(
      S3Client analyticsExportS3,
      S3Presigner analyticsExportPresigner,
      @Value("${mission.analytics.export.bucket:analytics-export}") String bucket,
      @Value("${mission.analytics.export.url-ttl:PT15M}") Duration urlTtl) {
    return new S3ExportStore(analyticsExportS3, analyticsExportPresigner, bucket, urlTtl);
  }

  @Bean
  ExportPorts.Files analyticsExportFiles(
      @Value("${mission.analytics.export.gotenberg-url}") String gotenbergUrl,
      ResilientClientFactory clients,
      ObjectProvider<RestClient.Builder> builders,
      ObjectMapper json,
      Clock clock) {
    RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
    return new ExportFiles(
        clients.create("gotenberg", builder.clone().baseUrl(gotenbergUrl).build()), json, clock);
  }

  @Bean
  ExportPorts.Jobs analyticsExportJobs(TenantCache cache, ObjectMapper json) {
    return new ValkeyExportJobs(cache, json);
  }

  @Bean
  ExportPorts.Audit analyticsExportAudit(AuditLogger audit) {
    return new AuditExports(audit);
  }

  /** Luồng nền dựng tệp: số luồng cố định, hàng đợi có giới hạn; đầy thì tác vụ báo bận. */
  @Bean(destroyMethod = "shutdown")
  ExecutorService analyticsExportExecutor(
      @Value("${mission.analytics.export.workers:4}") int workers) {
    return new ThreadPoolExecutor(
        workers,
        workers,
        60,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64),
        r -> {
          Thread t = new Thread(r, "analytics-export");
          t.setDaemon(true);
          return t;
        });
  }

  @Bean
  ExportService exportService(
      TraceService traces,
      QueryService queries,
      DashboardService dashboards,
      ScopeService scopes,
      SemanticModelService models,
      ExportPorts.Files analyticsExportFiles,
      ExportPorts.Store analyticsExportStore,
      ExportPorts.Jobs analyticsExportJobs,
      ExportPorts.Audit analyticsExportAudit,
      ExecutorService analyticsExportExecutor,
      Clock clock) {
    return new ExportService(
        traces,
        queries,
        dashboards,
        scopes,
        models,
        analyticsExportFiles,
        analyticsExportStore,
        analyticsExportJobs,
        analyticsExportAudit,
        analyticsExportExecutor,
        clock);
  }

  @Bean
  JobSource analyticsExportJobSource(ExportService exportService) {
    return new ExportJobSource(exportService);
  }
}
