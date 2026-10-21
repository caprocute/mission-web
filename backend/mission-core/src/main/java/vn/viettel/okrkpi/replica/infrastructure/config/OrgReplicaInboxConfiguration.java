package vn.viettel.okrkpi.replica.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.replica.infrastructure.persistence.OrgReplicaJdbc;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxPurgeJob;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * Inbox của mission-core ghi {@code org_replica.inbox_*} bằng {@code orgReplicaDataSource} và
 * {@code orgReplicaTransactionManager} (tự cấu hình inbox của starter chỉ biết DataSource chính, là
 * {@code taskDataSource}). Bật khi {@code okrkpi.inbox.schema=org_replica}.
 */
@Configuration(proxyBeanMethods = false)
public class OrgReplicaInboxConfiguration {

  @Bean
  OrgReplicaJdbc orgReplicaJdbc(@Qualifier("orgReplicaDataSource") DataSource dataSource) {
    return new OrgReplicaJdbc(new JdbcTemplate(dataSource));
  }

  @Bean
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema", havingValue = "org_replica")
  InboxMessageProcessor inboxMessageProcessor(
      OrgReplicaJdbc jdbc,
      @Qualifier("orgReplicaTransactionManager") PlatformTransactionManager tm,
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      ObjectProvider<Clock> clock) {
    return new InboxMessageProcessor(
        jdbc.template(),
        tm,
        properties.schema(),
        deserializer,
        retryDlqPublisher,
        metrics,
        properties.retry(),
        clock.getIfAvailable(Clock::systemUtc));
  }

  @Bean
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema", havingValue = "org_replica")
  InboxPurgeJob inboxPurgeJob(
      OrgReplicaJdbc jdbc,
      @Qualifier("orgReplicaTransactionManager") PlatformTransactionManager tm,
      InboxProperties properties) {
    return new InboxPurgeJob(jdbc.template(), tm, properties.schema(), properties.purge());
  }
}
