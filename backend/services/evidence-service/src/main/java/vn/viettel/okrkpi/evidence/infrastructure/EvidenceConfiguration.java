package vn.viettel.okrkpi.evidence.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.evidence.application.E2eeFileAccess;
import vn.viettel.okrkpi.evidence.application.FileAccessGuard;
import vn.viettel.okrkpi.evidence.application.FileEvents;
import vn.viettel.okrkpi.evidence.application.FileNameCipher;
import vn.viettel.okrkpi.evidence.application.IngestService;
import vn.viettel.okrkpi.evidence.application.MalwareScanner;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.OcrContentService;
import vn.viettel.okrkpi.evidence.application.OcrQueue;
import vn.viettel.okrkpi.evidence.application.OcrService;
import vn.viettel.okrkpi.evidence.application.OcrTextCipher;
import vn.viettel.okrkpi.evidence.application.PreviewQueryService;
import vn.viettel.okrkpi.evidence.application.PreviewQueue;
import vn.viettel.okrkpi.evidence.application.PreviewService;
import vn.viettel.okrkpi.evidence.application.ScanQueue;
import vn.viettel.okrkpi.evidence.application.ScanResultService;
import vn.viettel.okrkpi.evidence.application.ScanService;
import vn.viettel.okrkpi.evidence.application.StoredFiles;
import vn.viettel.okrkpi.evidence.application.TenantBucketService;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.evidence.application.UploadSessions;
import vn.viettel.okrkpi.evidence.infrastructure.crypto.FieldEncryptorFileNameCipher;
import vn.viettel.okrkpi.evidence.infrastructure.crypto.FieldEncryptorOcrTextCipher;
import vn.viettel.okrkpi.evidence.infrastructure.e2ee.MissionCoreE2eeFileAccess;
import vn.viettel.okrkpi.evidence.infrastructure.events.OutboxFileEvents;
import vn.viettel.okrkpi.evidence.infrastructure.events.OutboxScanEvents;
import vn.viettel.okrkpi.evidence.infrastructure.ocr.HttpOcrGateway;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcOcrQueue;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcPreviewQueue;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcScanQueue;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcStoredFiles;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcUploadSessions;
import vn.viettel.okrkpi.evidence.infrastructure.preview.PdfboxGotenbergRendering;
import vn.viettel.okrkpi.evidence.infrastructure.scanning.ClamdScanner;
import vn.viettel.okrkpi.evidence.infrastructure.scheduling.ExpiredUploadSweeper;
import vn.viettel.okrkpi.evidence.infrastructure.scheduling.MalwareScanWorker;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/** Lắp ráp tải lên nhiều phần (DV-08-02), quét mã độc (DV-08-04) và xem trước (DV-08-06). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EvidenceConfiguration {

  @Bean
  UploadSessions uploadSessions(JdbcClient jdbc) {
    return new JdbcUploadSessions(jdbc);
  }

  @Bean
  StoredFiles storedFiles(JdbcClient jdbc) {
    return new JdbcStoredFiles(jdbc);
  }

  @Bean
  FileEvents fileEvents(OutboxWriter outbox) {
    return new OutboxFileEvents(outbox);
  }

  @Bean
  FileNameCipher fileNameCipher(ObjectProvider<FieldEncryptor> encryptor) {
    return (tenant, objectId, name) ->
        new FieldEncryptorFileNameCipher(encryptor.getObject()).seal(tenant, objectId, name);
  }

  @Bean
  OcrTextCipher ocrTextCipher(ObjectProvider<FieldEncryptor> encryptor) {
    return (tenant, version, text) ->
        new FieldEncryptorOcrTextCipher(encryptor.getObject()).seal(tenant, version, text);
  }

  @Bean
  FileAccessGuard fileAccessGuard() {
    return new FileAccessGuard();
  }

  @Bean
  UploadService uploadService(
      UploadSessions sessions,
      StoredFiles files,
      FileEvents events,
      FileNameCipher names,
      ObjectStorage storage,
      TenantBucketService buckets,
      FileAccessGuard guard,
      PlatformTransactionManager transactions,
      ObjectProvider<Clock> clock,
      ObjectProvider<E2eeFileAccess> e2eeAccess,
      @Value("${okrkpi.evidence.storage.cell}") String cell,
      @Value("${okrkpi.evidence.upload.max-file-bytes:52428800}") long maxFileBytes,
      @Value("${okrkpi.evidence.upload.part-size:8388608}") int partSize,
      @Value("${okrkpi.evidence.upload.session-ttl:PT24H}") Duration sessionTtl,
      @Value("${okrkpi.evidence.storage.lock-days}") int lockDays) {
    return new UploadService(
        sessions,
        files,
        events,
        names,
        storage,
        buckets,
        guard,
        new TransactionTemplate(transactions),
        new UploadService.Settings(cell, maxFileBytes, partSize, sessionTtl, lockDays),
        clock.getIfAvailable(Clock::systemUTC),
        e2eeAccess.getIfAvailable());
  }

  /** CORE-03-04: nhận tệp gốc văn bản đã ban hành do integration-gateway chuyển sang. */
  @Bean
  IngestService ingestService(
      StoredFiles files,
      FileEvents events,
      FileNameCipher names,
      ObjectStorage storage,
      TenantBucketService buckets,
      PlatformTransactionManager transactions,
      ObjectProvider<Clock> clock,
      @Value("${okrkpi.evidence.storage.cell}") String cell,
      @Value("${okrkpi.evidence.upload.max-file-bytes:52428800}") long maxFileBytes,
      @Value("${okrkpi.evidence.upload.part-size:8388608}") int partSize,
      @Value("${okrkpi.evidence.upload.session-ttl:PT24H}") Duration sessionTtl,
      @Value("${okrkpi.evidence.storage.lock-days}") int lockDays) {
    return new IngestService(
        files,
        events,
        names,
        storage,
        buckets,
        new TransactionTemplate(transactions),
        new UploadService.Settings(cell, maxFileBytes, partSize, sessionTtl, lockDays),
        clock.getIfAvailable(Clock::systemUTC));
  }

  /**
   * CORE-07-06: hỏi MOD-03 người gọi còn giữ bản bọc của tệp C3 trước khi cấp URL tải bản mã. Không
   * khai {@code okrkpi.evidence.e2ee-access.base-url} thì chỉ người tải lên mở được bản mã.
   */
  @Bean
  @ConditionalOnProperty(prefix = "okrkpi.evidence.e2ee-access", name = "base-url")
  E2eeFileAccess e2eeFileAccess(
      ObjectProvider<Clock> clock,
      @Value("${okrkpi.evidence.e2ee-access.base-url}") String baseUrl,
      @Value("${okrkpi.evidence.e2ee-access.token-url}") String tokenUrl,
      @Value("${okrkpi.evidence.e2ee-access.client-id:evidence-service}") String clientId,
      @Value("${okrkpi.evidence.e2ee-access.client-secret:}") String clientSecret) {
    return new MissionCoreE2eeFileAccess(
        RestClient.builder().baseUrl(baseUrl).build(),
        new MissionCoreE2eeFileAccess.Token(
            RestClient.create(),
            tokenUrl,
            clientId,
            clientSecret,
            clock.getIfAvailable(Clock::systemUTC)));
  }

  @Bean
  ScanQueue scanQueue(JdbcClient jdbc, StoredFiles files) {
    return new JdbcScanQueue(jdbc, files);
  }

  @Bean
  PreviewQueue previewQueue(JdbcClient jdbc, StoredFiles files) {
    return new JdbcPreviewQueue(jdbc, files);
  }

  @Bean
  OcrQueue ocrQueue(JdbcClient jdbc, StoredFiles files) {
    return new JdbcOcrQueue(jdbc, files);
  }

  /** DV-08-05: cấp nội dung tệp cho ai-gateway đọc chữ qua cổng nội bộ. */
  @Bean
  OcrContentService ocrContentService(
      StoredFiles files, ObjectStorage storage, PlatformTransactionManager transactions) {
    return new OcrContentService(files, storage, new TransactionTemplate(transactions));
  }

  @Bean
  PreviewQueryService previewQueryService(
      PreviewQueue queue,
      ObjectStorage storage,
      FileAccessGuard guard,
      PlatformTransactionManager transactions,
      ObjectProvider<Clock> clock) {
    return new PreviewQueryService(
        queue,
        storage,
        guard,
        new TransactionTemplate(transactions),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  ScanResultService scanResultService(
      ScanQueue queue, FileAccessGuard guard, PlatformTransactionManager transactions) {
    return new ScanResultService(queue, guard, new TransactionTemplate(transactions));
  }

  /** Worker quét (DV-08-04) chỉ bật ở Deployment worker; pod API đặt {@code scan.enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "okrkpi.evidence.scan.enabled", havingValue = "true")
  static class ScanWorkerConfiguration {

    @Bean
    MalwareScanner malwareScanner(
        @Value("${okrkpi.evidence.scan.clamd-host}") String host,
        @Value("${okrkpi.evidence.scan.clamd-port:3310}") int port,
        @Value("${okrkpi.evidence.scan.clamd-timeout:PT60S}") Duration timeout) {
      return new ClamdScanner(host, port, timeout);
    }

    @Bean
    ScanService scanService(
        ScanQueue queue,
        OutboxWriter outbox,
        ObjectStorage storage,
        MalwareScanner scanner,
        PlatformTransactionManager transactions,
        ObjectProvider<Clock> clock,
        MeterRegistry meters,
        @Value("${okrkpi.evidence.scan.lease:PT10M}") Duration lease,
        @Value("${okrkpi.evidence.scan.retry-base:PT30S}") Duration retryBase,
        @Value("${okrkpi.evidence.scan.max-attempts:5}") int maxAttempts) {
      return new ScanService(
          queue,
          new OutboxScanEvents(outbox),
          storage,
          scanner,
          new TransactionTemplate(transactions),
          new ScanService.Settings(lease, retryBase, maxAttempts),
          clock.getIfAvailable(Clock::systemUTC),
          meters);
    }

    @Bean
    PreviewService previewService(
        PreviewQueue queue,
        ObjectStorage storage,
        PlatformTransactionManager transactions,
        ObjectProvider<Clock> clock,
        @Value("${okrkpi.evidence.preview.gotenberg-url}") String gotenbergUrl,
        @Value("${okrkpi.evidence.preview.gotenberg-timeout:PT60S}") Duration timeout,
        @Value("${okrkpi.evidence.preview.dpi:150}") float dpi,
        @Value("${okrkpi.evidence.preview.max-pages:300}") int maxPages,
        @Value("${okrkpi.evidence.preview.max-width:1240}") int maxWidth,
        @Value("${okrkpi.evidence.preview.work-dir:${java.io.tmpdir}/xem-truoc}") String workDir,
        @Value("${okrkpi.evidence.scan.lease:PT10M}") Duration lease,
        @Value("${okrkpi.evidence.scan.retry-base:PT30S}") Duration retryBase,
        @Value("${okrkpi.evidence.scan.max-attempts:5}") int maxAttempts) {
      var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
      factory.setReadTimeout(Math.toIntExact(timeout.toMillis()));
      RestClient gotenberg =
          RestClient.builder().baseUrl(gotenbergUrl).requestFactory(factory).build();
      return new PreviewService(
          queue,
          storage,
          new PdfboxGotenbergRendering(gotenberg),
          new TransactionTemplate(transactions),
          new PreviewService.Settings(
              lease, retryBase, maxAttempts, dpi, maxPages, maxWidth, Path.of(workDir)),
          clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * DV-08-05: đọc chữ qua ai-gateway, bật khi đặt {@code okrkpi.evidence.ocr.ai-gateway-url};
     * token dịch vụ lấy bằng client credentials (bí mật từ OpenBao qua ExternalSecret).
     */
    @Bean
    @ConditionalOnExpression("!'${okrkpi.evidence.ocr.ai-gateway-url:}'.isBlank()")
    OcrService ocrService(
        OcrQueue queue,
        OcrTextCipher cipher,
        ObjectProvider<ObjectMapper> mapper,
        PlatformTransactionManager transactions,
        ObjectProvider<Clock> clock,
        @Value("${okrkpi.evidence.ocr.ai-gateway-url}") String baseUrl,
        @Value("${okrkpi.evidence.ocr.token-url:}") String tokenUrl,
        @Value("${okrkpi.evidence.ocr.client-id:evidence-service}") String clientId,
        @Value("${okrkpi.evidence.ocr.client-secret:}") String clientSecret,
        @Value("${okrkpi.evidence.ocr.timeout:PT120S}") Duration timeout,
        @Value("${okrkpi.evidence.ocr.lease:PT10M}") Duration lease,
        @Value("${okrkpi.evidence.ocr.retry-base:PT30S}") Duration retryBase,
        @Value("${okrkpi.evidence.ocr.max-attempts:3}") int maxAttempts) {
      Clock c = clock.getIfAvailable(Clock::systemUTC);
      return new OcrService(
          queue,
          HttpOcrGateway.create(
              mapper.getIfAvailable(ObjectMapper::new),
              baseUrl,
              tokenUrl,
              clientId,
              clientSecret,
              timeout,
              c),
          cipher,
          new TransactionTemplate(transactions),
          new OcrService.Settings(lease, retryBase, maxAttempts),
          c);
    }

    @Bean
    MalwareScanWorker malwareScanWorker(
        ScanService scans, PreviewService previews, ObjectProvider<OcrService> ocr) {
      return new MalwareScanWorker(scans, previews, ocr.getIfAvailable());
    }
  }

  @Bean
  @ConditionalOnProperty(
      name = "okrkpi.evidence.upload.sweep-enabled",
      havingValue = "true",
      matchIfMissing = true)
  ExpiredUploadSweeper expiredUploadSweeper(UploadService uploads) {
    return new ExpiredUploadSweeper(uploads);
  }
}
