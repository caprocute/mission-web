package vn.viettel.okrkpi.task.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.RecoveryKeySettingApi;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.infrastructure.e2ee.OrgRecoveryKeyClient;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService;
import vn.viettel.okrkpi.task.application.secret.port.RecoveryKeys;
import vn.viettel.okrkpi.task.application.secret.port.RecoveryRecords;

/** CORE-07-06: nội dung nhiệm vụ mật C3 và khoá đã bọc. */
@Configuration(proxyBeanMethods = false)
public class TaskSecretContentConfiguration {

  @Bean
  JdbcTaskSecretContents taskSecretContents(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcTaskSecretContents(JdbcClient.create(dataSource));
  }

  @Bean
  RecoveryKeys taskRecoveryKeys(RecoveryKeySettingApi settings) {
    return new TenantSettingRecoveryKeys(settings);
  }

  /**
   * Biên bản khôi phục đọc từ SVC-04 khi có client; không có client thì nạp khoá bọc khôi phục trả
   * 503.
   */
  @Bean
  TaskSecretContentService taskSecretContentService(
      JdbcTaskSecretContents taskSecretContents,
      RecoveryKeys taskRecoveryKeys,
      ObjectProvider<OrgRecoveryKeyClient> org,
      AuditLogger audit) {
    OrgRecoveryKeyClient client = org.getIfAvailable();
    if (client == null) {
      return new TaskSecretContentService(
          taskSecretContents, taskRecoveryKeys, audit, java.time.Clock.systemUTC());
    }
    RecoveryRecords records =
        (tenant, id) ->
            client
                .recovery(tenant, id)
                .map(
                    r ->
                        new RecoveryRecords.Recovery(
                            r.objectType(),
                            r.objectId(),
                            r.newRecipientId(),
                            r.newKeyFingerprint(),
                            r.recordedAt()));
    return new TaskSecretContentService(
        taskSecretContents, taskRecoveryKeys, records, audit, java.time.Clock.systemUTC());
  }
}
