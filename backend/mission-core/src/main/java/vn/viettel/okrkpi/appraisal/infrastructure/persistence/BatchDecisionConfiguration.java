package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

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
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionSubmission;
import vn.viettel.okrkpi.appraisal.application.decision.DecisionSigningService;
import vn.viettel.okrkpi.appraisal.application.decision.port.DecisionNotices;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.infrastructure.evidence.ProductEvidenceAcceptors;
import vn.viettel.okrkpi.appraisal.infrastructure.job.BatchDecisionJobs;
import vn.viettel.okrkpi.appraisal.infrastructure.job.ComplaintDeadlineJob;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * CORE-04-07, CORE-04-08: quyết định xếp loại hàng loạt và ký lô trên {@code appraisalDataSource}.
 */
@Configuration(proxyBeanMethods = false)
public class BatchDecisionConfiguration {

  @Bean
  JdbcBatchDecisionRepository batchDecisionRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcBatchDecisionRepository(JdbcClient.create(dataSource));
  }

  @Bean
  BatchDecisionService batchDecisionService(
      JdbcAppraisalProfileRepository appraisalProfileRepository,
      JdbcBatchDecisionRepository batchDecisionRepository,
      AppraisalProfileService appraisalProfileService,
      TextSealer appraisalTextSealer,
      ObjectProvider<EvidenceQueryApi> evidenceQuery,
      ObjectProvider<PolicyQueryApi> policies) {
    EvidenceQueryApi evidence = evidenceQuery.getIfAvailable();
    return new BatchDecisionService(
        appraisalProfileRepository,
        batchDecisionRepository,
        appraisalProfileService,
        appraisalTextSealer,
        evidence == null ? null : new ProductEvidenceAcceptors(evidence),
        policies.getIfAvailable(),
        java.time.Clock.systemUTC());
  }

  @Bean
  BatchDecisionSubmission batchDecisionSubmission(
      BatchDecisionService batchDecisionService, SignRequests appraisalSignRequests) {
    return new BatchDecisionSubmission(batchDecisionService, appraisalSignRequests);
  }

  @Bean
  DecisionNotices appraisalDecisionNotices(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxDecisionNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "appraisal",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  DecisionSigningService decisionSigningService(
      JdbcAppraisalProfileRepository appraisalProfileRepository,
      JdbcBatchDecisionRepository batchDecisionRepository,
      AppraisalProfileService appraisalProfileService,
      DecisionNotices appraisalDecisionNotices,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<WorkingCalendarApi> calendar) {
    return new DecisionSigningService(
        appraisalProfileRepository,
        batchDecisionRepository,
        appraisalProfileService,
        appraisalDecisionNotices,
        policies.getIfAvailable(),
        calendar.getIfAvailable(),
        java.time.Clock.systemUTC());
  }

  @Bean
  BatchDecisionJobs batchDecisionJobs(BatchDecisionService batchDecisionService) {
    return new BatchDecisionJobs(batchDecisionService);
  }

  /**
   * Khoá hồ sơ hết hạn khiếu nại; tắt bằng {@code
   * mission.appraisal.complaint-deadline.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.appraisal.complaint-deadline",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class DeadlineJob {

    @Bean
    ComplaintDeadlineJob appraisalComplaintDeadlineJob(
        DecisionSigningService decisionSigningService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new ComplaintDeadlineJob(
          decisionSigningService, directory, cell, java.time.Clock.systemUTC());
    }
  }
}
