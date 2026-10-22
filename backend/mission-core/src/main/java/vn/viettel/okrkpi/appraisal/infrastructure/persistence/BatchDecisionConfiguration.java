package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionSubmission;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.infrastructure.evidence.ProductEvidenceAcceptors;
import vn.viettel.okrkpi.appraisal.infrastructure.job.BatchDecisionJobs;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;

/** CORE-04-07: quyết định xếp loại hàng loạt và ký lô trên {@code appraisalDataSource}. */
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
  BatchDecisionJobs batchDecisionJobs(BatchDecisionService batchDecisionService) {
    return new BatchDecisionJobs(batchDecisionService);
  }
}
