package vn.viettel.okrkpi.evidence.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.evidence.application.FileAccessGuard;
import vn.viettel.okrkpi.evidence.application.FileEvents;
import vn.viettel.okrkpi.evidence.application.FileNameCipher;
import vn.viettel.okrkpi.evidence.application.MalwareScanner;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.ScanQueue;
import vn.viettel.okrkpi.evidence.application.ScanResultService;
import vn.viettel.okrkpi.evidence.application.ScanService;
import vn.viettel.okrkpi.evidence.application.StoredFiles;
import vn.viettel.okrkpi.evidence.application.TenantBucketService;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.evidence.application.UploadSessions;
import vn.viettel.okrkpi.evidence.infrastructure.crypto.FieldEncryptorFileNameCipher;
import vn.viettel.okrkpi.evidence.infrastructure.events.OutboxFileEvents;
import vn.viettel.okrkpi.evidence.infrastructure.events.OutboxScanEvents;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcScanQueue;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcStoredFiles;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcUploadSessions;
import vn.viettel.okrkpi.evidence.infrastructure.scanning.ClamdScanner;
import vn.viettel.okrkpi.evidence.infrastructure.scheduling.ExpiredUploadSweeper;
import vn.viettel.okrkpi.evidence.infrastructure.scheduling.MalwareScanWorker;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/** Lắp ráp tải lên nhiều phần (DV-08-02) và quét mã độc ở nền (DV-08-04). */
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
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  ScanQueue scanQueue(JdbcClient jdbc, StoredFiles files) {
    return new JdbcScanQueue(jdbc, files);
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
    MalwareScanWorker malwareScanWorker(ScanService scans) {
      return new MalwareScanWorker(scans);
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
