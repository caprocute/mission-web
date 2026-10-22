package vn.viettel.okrkpi.evidence.infrastructure;

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
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.application.StoredFiles;
import vn.viettel.okrkpi.evidence.application.TenantBucketService;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.evidence.application.UploadSessions;
import vn.viettel.okrkpi.evidence.infrastructure.crypto.FieldEncryptorFileNameCipher;
import vn.viettel.okrkpi.evidence.infrastructure.events.OutboxFileEvents;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcStoredFiles;
import vn.viettel.okrkpi.evidence.infrastructure.persistence.JdbcUploadSessions;
import vn.viettel.okrkpi.evidence.infrastructure.scheduling.ExpiredUploadSweeper;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/** Lắp ráp tải lên nhiều phần (DV-08-02). */
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
  @ConditionalOnProperty(
      name = "okrkpi.evidence.upload.sweep-enabled",
      havingValue = "true",
      matchIfMissing = true)
  ExpiredUploadSweeper expiredUploadSweeper(UploadService uploads) {
    return new ExpiredUploadSweeper(uploads);
  }
}
