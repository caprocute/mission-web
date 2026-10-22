package vn.viettel.okrkpi.product.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.application.evidence.EvidenceReminderService;
import vn.viettel.okrkpi.product.infrastructure.client.ReplicaReviewerSupervisors;
import vn.viettel.okrkpi.product.infrastructure.scheduling.EvidenceReminderJob;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/** CORE-03-08: nhắc quá hạn nghiệm thu (BR-07-12) trên {@code productDataSource}. */
@Configuration(proxyBeanMethods = false)
public class EvidenceReminderConfiguration {

  @Bean
  JdbcReminderRepository reminderRepository(@Qualifier("productDataSource") DataSource dataSource) {
    return new JdbcReminderRepository(JdbcClient.create(dataSource));
  }

  @Bean
  OutboxReminderNotices reminderNotices(
      @Qualifier("productDataSource") DataSource dataSource,
      @Value("${mission.product.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxReminderNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "product",
            serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  EvidenceReminderService evidenceReminderService(
      JdbcReminderRepository reminderRepository,
      OutboxReminderNotices reminderNotices,
      WorkingCalendarApi calendar,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<OrgDirectoryApi> org) {
    OrgDirectoryApi directory = org.getIfAvailable();
    return new EvidenceReminderService(
        reminderRepository,
        reminderNotices,
        calendar,
        policies.getIfAvailable(),
        directory == null ? null : new ReplicaReviewerSupervisors(directory));
  }

  /** Tác vụ hằng ngày; tắt bằng {@code mission.product.acceptance-reminder.enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.product.acceptance-reminder",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Job {

    @Bean
    EvidenceReminderJob evidenceReminderJob(
        EvidenceReminderService evidenceReminderService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new EvidenceReminderJob(
          evidenceReminderService, directory, cell, java.time.Clock.systemUTC());
    }
  }
}
