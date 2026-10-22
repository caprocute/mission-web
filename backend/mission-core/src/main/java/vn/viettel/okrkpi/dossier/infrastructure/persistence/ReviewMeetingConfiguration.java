package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingPreparation;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-06-02: hội nghị kiểm điểm trên {@code dossierDataSource}, EVT-21 qua {@code
 * dossier.outbox_event}. Việc chạy kèm khi tạo hội nghị ({@link MeetingPreparation}) là các bean
 * của CORE-06-05 và cổng SVC-11; chưa có bean nào thì bỏ qua.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewMeetingConfiguration {

  @Bean
  JdbcReviewMeetingRepository reviewMeetingRepository(
      @Qualifier("dossierDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcReviewMeetingRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  MeetingNotices reviewMeetingNotices(
      @Qualifier("dossierDataSource") DataSource dataSource,
      @Value("${mission.dossier.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxMeetingNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "dossier",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  ReviewMeetingService reviewMeetingService(
      JdbcReviewMeetingRepository reviewMeetingRepository,
      PeriodApi periodApi,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<OrgDirectoryApi> directory,
      MeetingNotices reviewMeetingNotices,
      ObjectProvider<MeetingPreparation> preparations) {
    return new ReviewMeetingService(
        reviewMeetingRepository,
        periodApi,
        calendar::getIfAvailable,
        directory::getIfAvailable,
        reviewMeetingNotices,
        () -> preparations.orderedStream().toList(),
        java.time.Clock.systemUTC());
  }
}
