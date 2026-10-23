package vn.viettel.okrkpi.task.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService;
import vn.viettel.okrkpi.task.application.secret.port.RecoveryKeys;

/** CORE-07-06: nội dung nhiệm vụ mật C3 và khoá đã bọc. */
@Configuration(proxyBeanMethods = false)
public class TaskSecretContentConfiguration {

  @Bean
  JdbcTaskSecretContents taskSecretContents(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcTaskSecretContents(JdbcClient.create(dataSource));
  }

  @Bean
  RecoveryKeys taskRecoveryKeys(TenantSettingApi settings) {
    return new TenantSettingRecoveryKeys(settings);
  }

  @Bean
  TaskSecretContentService taskSecretContentService(
      JdbcTaskSecretContents taskSecretContents, RecoveryKeys taskRecoveryKeys, AuditLogger audit) {
    return new TaskSecretContentService(
        taskSecretContents, taskRecoveryKeys, audit, java.time.Clock.systemUTC());
  }
}
