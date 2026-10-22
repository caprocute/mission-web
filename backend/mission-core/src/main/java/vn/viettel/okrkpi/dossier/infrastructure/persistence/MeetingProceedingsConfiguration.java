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
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.MinutesRendering;
import vn.viettel.okrkpi.dossier.application.remediation.RemediationService;
import vn.viettel.okrkpi.dossier.infrastructure.client.PendingMinutesRendering;
import vn.viettel.okrkpi.dossier.infrastructure.listener.MeetingMinutesConsumer;
import vn.viettel.okrkpi.dossier.infrastructure.web.MinutesJobs;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;

/**
 * CORE-06-04: biểu quyết, biên bản, kết thúc hội nghị và theo dõi khắc phục trên {@code
 * dossierDataSource}; consumer EVT-17, EVT-18 ghi inbox {@code dossier} (tắt bằng {@code
 * mission.dossier.minutes.consumer.enabled=false}). Cổng sinh biên bản mặc định chờ SVC-11 công bố
 * mẫu biên bản.
 */
@Configuration(proxyBeanMethods = false)
public class MeetingProceedingsConfiguration {

  @Bean
  JdbcRemediationItemRepository remediationItemRepository(
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new JdbcRemediationItemRepository(JdbcClient.create(dataSource));
  }

  @Bean
  @ConditionalOnMissingBean(MinutesRendering.class)
  MinutesRendering minutesRendering() {
    return new PendingMinutesRendering();
  }

  @Bean
  MeetingProceedingsService meetingProceedingsService(
      JdbcReviewMeetingRepository reviewMeetingRepository,
      JdbcReviewCommentRepository reviewCommentRepository,
      JdbcRemediationItemRepository remediationItemRepository,
      CommentSealer sealer,
      PeriodApi periodApi,
      ObjectProvider<WorkingCalendarApi> calendar,
      MeetingNotices reviewMeetingNotices,
      MinutesRendering minutesRendering,
      ObjectProvider<ObjectMapper> json) {
    return new MeetingProceedingsService(
        reviewMeetingRepository,
        reviewCommentRepository,
        remediationItemRepository,
        sealer,
        periodApi,
        calendar::getIfAvailable,
        reviewMeetingNotices,
        minutesRendering,
        json.getIfAvailable(ObjectMapper::new),
        java.time.Clock.systemUTC());
  }

  @Bean
  RemediationService remediationService(
      JdbcReviewMeetingRepository reviewMeetingRepository,
      JdbcRemediationItemRepository remediationItemRepository,
      CommentSealer sealer) {
    return new RemediationService(
        reviewMeetingRepository, remediationItemRepository, sealer, java.time.Clock.systemUTC());
  }

  @Bean
  MinutesJobs minutesJobs(MeetingProceedingsService meetingProceedingsService) {
    return new MinutesJobs(meetingProceedingsService);
  }

  /**
   * Inbox riêng của schema {@code dossier}; dùng chung bộ giải mã, thử lại, thư chết của starter.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.dossier.minutes.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  MeetingMinutesConsumer meetingMinutesConsumer(
      MeetingProceedingsService meetingProceedingsService,
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
    return new MeetingMinutesConsumer(
        meetingProceedingsService, processor, containers, props.listenerAutoStartup());
  }
}
