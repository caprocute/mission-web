package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseFactsSource;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRuleSource;
import vn.viettel.okrkpi.dossier.infrastructure.listener.SpecialCaseAssignmentConsumer;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;

/**
 * CORE-06-05: trường hợp đặc thù trên {@code dossierDataSource}. Bảng quy tắc mặc định là gói khối
 * Đảng v2.0, dữ kiện đọc từ bản sao tổ chức; consumer EVT-15 ghi inbox {@code dossier} (tắt bằng
 * {@code mission.dossier.special-case.consumer.enabled=false}).
 */
@Configuration(proxyBeanMethods = false)
public class SpecialCaseConfiguration {

  @Bean
  JdbcSpecialCaseRepository specialCaseRepository(
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new JdbcSpecialCaseRepository(JdbcClient.create(dataSource));
  }

  @Bean
  @ConditionalOnMissingBean(SpecialCaseRuleSource.class)
  SpecialCaseRuleSource specialCaseRuleSource() {
    return new DefaultSpecialCaseRules();
  }

  @Bean
  @ConditionalOnMissingBean(SpecialCaseFactsSource.class)
  SpecialCaseFactsSource specialCaseFactsSource(ObjectProvider<OrgDirectoryApi> directory) {
    return new ReplicaSpecialCaseFacts(directory::getIfAvailable);
  }

  @Bean
  SpecialCaseService specialCaseService(
      JdbcSpecialCaseRepository specialCaseRepository,
      SpecialCaseRuleSource specialCaseRuleSource,
      SpecialCaseFactsSource specialCaseFactsSource,
      PeriodApi periodApi,
      ObjectProvider<OrgDirectoryApi> directory,
      CommentSealer sealer,
      ObjectProvider<ObjectMapper> json) {
    return new SpecialCaseService(
        specialCaseRepository,
        specialCaseRuleSource,
        specialCaseFactsSource,
        periodApi,
        directory::getIfAvailable,
        sealer,
        json.getIfAvailable(ObjectMapper::new),
        java.time.Clock.systemUTC());
  }

  @Bean
  SpecialCaseMeetingPreparation specialCaseMeetingPreparation(
      SpecialCaseService specialCaseService,
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new SpecialCaseMeetingPreparation(specialCaseService, JdbcClient.create(dataSource));
  }

  /** Inbox riêng của schema {@code dossier}, như consumer biên bản của CORE-06-04. */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.dossier.special-case.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  SpecialCaseAssignmentConsumer specialCaseAssignmentConsumer(
      SpecialCaseService specialCaseService,
      @Qualifier("dossierDataSource") DataSource dataSource,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<InboxProperties> properties,
      ObjectProvider<InboxPayloadDeserializer> deserializer,
      ObjectProvider<InboxRetryDlqPublisher> retryDlq,
      ObjectProvider<InboxMetrics> metrics,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers) {
    InboxProperties props = properties.getIfAvailable();
    InboxPayloadDeserializer d = deserializer.getIfAvailable();
    InboxRetryDlqPublisher r = retryDlq.getIfAvailable();
    InboxMetrics m = metrics.getIfAvailable();
    if (props == null || d == null || r == null || m == null) {
      return null;
    }
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dataSource),
            tm,
            "dossier",
            d,
            r,
            m,
            props.retry(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    return new SpecialCaseAssignmentConsumer(
        specialCaseService, processor, containers, props.listenerAutoStartup());
  }
}
