package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections;
import vn.viettel.okrkpi.task.application.document.IncomingDocService;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocContents;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.application.similar.SimilarTaskService;
import vn.viettel.okrkpi.task.infrastructure.client.HttpDocExtractions;
import vn.viettel.okrkpi.task.infrastructure.client.HttpIncomingDocContents;
import vn.viettel.okrkpi.task.infrastructure.listener.IncomingDocConsumer;

/**
 * CORE-02-16: văn bản chỉ đạo đến (EVT-24) và nhiệm vụ từ văn bản. Để trống {@code
 * mission.task.integ.base-url} (mặc định theo {@code mission.integ.base-url}) thì văn bản chỉ có
 * siêu dữ liệu của EVT-24; để trống {@code mission.task.ai.base-url} (mặc định theo {@code
 * mission.okr.ai.base-url}) thì văn bản coi như đang trích. Bean {@link IncomingDocContents},
 * {@link DocExtractions} khác (kiểm thử) được ưu tiên.
 */
@Configuration(proxyBeanMethods = false)
public class IncomingDocConfiguration {

  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  @Bean
  JdbcIncomingDocs incomingDocs(
      @Qualifier("taskDataSource") DataSource dataSource, ObjectMapper json) {
    return new JdbcIncomingDocs(JdbcClient.create(dataSource), json);
  }

  @Bean
  IncomingDocService incomingDocService(
      IncomingDocs incomingDocs,
      TaskDraftService taskDraftService,
      TaskAssignService taskAssignService,
      ObjectProvider<SimilarTaskService> similar,
      TaskReminderNotices taskReminderNotices,
      TaskDirections taskDirections,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
      PeriodApi periods,
      OrgDirectoryApi org,
      @Value("${mission.task.integ.base-url:${mission.integ.base-url:}}") String integUrl,
      @Value("${mission.task.ai.base-url:${mission.okr.ai.base-url:}}") String aiUrl,
      @Value("${mission.task.ai.timeout:PT3S}") Duration aiTimeout,
      ObjectProvider<IncomingDocContents> contentsOverride,
      ObjectProvider<DocExtractions> extractionsOverride,
      ObjectProvider<ResilientClientFactory> factory,
      ObjectProvider<KeycloakAdminClient> keycloak) {
    IncomingDocContents contents = contentsOverride.getIfAvailable();
    ResilientClientFactory f = factory.getIfAvailable();
    if (contents == null && !integUrl.isBlank() && f != null) {
      contents =
          new HttpIncomingDocContents(
              f.create(HttpIncomingDocContents.INSTANCE, integUrl),
              () -> {
                KeycloakAdminClient k = keycloak.getIfAvailable();
                if (k == null) {
                  throw new DependencyUnavailableException(
                      "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
                }
                return k.serviceToken();
              });
    }
    DocExtractions extractions = extractionsOverride.getIfAvailable();
    if (extractions == null && !aiUrl.isBlank()) {
      extractions = new HttpDocExtractions(aiUrl, aiTimeout);
    }
    return new IncomingDocService(
        incomingDocs,
        contents,
        extractions,
        taskDraftService,
        taskAssignService,
        similar.getIfAvailable(),
        taskReminderNotices,
        periods,
        org,
        taskId ->
            incomingDocs
                .task(TenantContext.require(), taskId)
                .flatMap(
                    t -> taskDirections.lead(TenantContext.require(), t.periodBucket(), t.id()))
                .map(
                    lead ->
                        lead.personId() != null
                            ? List.of(lead.personId())
                            : org.headsOf(
                                TenantContext.require(), lead.orgUnitId(), LocalDate.now(ZONE)))
                .orElse(List.of()),
        perItem(tm),
        java.time.Clock.systemUTC());
  }

  private static TransactionTemplate perItem(PlatformTransactionManager tm) {
    TransactionTemplate t = new TransactionTemplate(tm);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return t;
  }

  /** Inbox riêng của schema {@code task} cho EVT-24, như consumer EVT-15. */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.task.incoming-doc.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  IncomingDocConsumer incomingDocConsumer(
      IncomingDocService incomingDocService,
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
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
            "task",
            d,
            r,
            m,
            props.retry(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    return new IncomingDocConsumer(
        incomingDocService, processor, containers, props.listenerAutoStartup());
  }
}
