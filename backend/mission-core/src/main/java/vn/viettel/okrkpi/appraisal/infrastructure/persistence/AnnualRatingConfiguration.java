package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualReassignmentService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualSetupService;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualNotices;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRecomputeRequests;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.api.TaskApi;

/**
 * CORE-04-10: hồ sơ năm, bảng xếp loại năm và giải thích liên thông; CORE-04-11: cảnh báo và đề
 * xuất bố trí lại.
 */
@Configuration(proxyBeanMethods = false)
public class AnnualRatingConfiguration {

  @Bean
  JdbcAnnualRatingRepository annualRatingRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcAnnualRatingRepository(JdbcClient.create(dataSource));
  }

  @Bean
  AnnualRecomputeRequests annualRecomputeRequests() {
    return new LoggingAnnualRecomputeRequests();
  }

  @Bean
  AnnualSetupService annualSetupService(
      JdbcAnnualRatingRepository annualRatingRepository,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<AppraisalSpecialCases> specialCases,
      ObjectProvider<AnnualNotices> notices) {
    return new AnnualSetupService(
        annualRatingRepository,
        periods.getIfAvailable(),
        policies.getIfAvailable(),
        specialCases.getIfAvailable(),
        notices.getIfAvailable());
  }

  @Bean
  AnnualNotices annualNotices(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxAnnualNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "appraisal",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  AnnualReassignmentService annualReassignmentService(
      JdbcAnnualRatingRepository annualRatingRepository,
      TaskApi tasks,
      PeriodApi periods,
      ObjectProvider<AuditLogger> audit,
      @Value("${mission.appraisal.reassign-due-days:30}") int dueDays,
      @Value("${mission.appraisal.zone:Asia/Ho_Chi_Minh}") String zone) {
    return new AnnualReassignmentService(
        annualRatingRepository,
        tasks,
        periods,
        audit.getIfAvailable(),
        java.time.Clock.systemUTC(),
        ZoneId.of(zone),
        dueDays);
  }

  @Bean
  AnnualRatingService annualRatingService(
      JdbcAnnualRatingRepository annualRatingRepository,
      JdbcScoreSnapshotReader scoreSnapshotReader) {
    return new AnnualRatingService(annualRatingRepository, scoreSnapshotReader);
  }
}
