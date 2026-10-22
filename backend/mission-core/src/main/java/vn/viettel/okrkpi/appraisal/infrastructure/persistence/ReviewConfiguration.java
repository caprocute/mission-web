package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewExportService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewSubmission;
import vn.viettel.okrkpi.appraisal.application.review.port.ProposalStep;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewNotices;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.infrastructure.evidence.ProductEvidenceAcceptors;
import vn.viettel.okrkpi.appraisal.infrastructure.job.FastExcelReviewSheets;
import vn.viettel.okrkpi.appraisal.infrastructure.job.InMemoryReviewExports;
import vn.viettel.okrkpi.appraisal.infrastructure.job.ReviewSignJobs;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-04-05: thẩm định, trả lại, đề xuất trên {@code appraisalDataSource}; nhóm người nghiệm thu
 * đọc qua {@code product :: api}; tác vụ ký và xuất danh sách.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

  /**
   * Khoá tham số bỏ qua cấp đề xuất trong Policy Pack (suy luận) trong lúc chờ {@code
   * flowable.wf_binding} của DV-06; không đặt thì không bỏ qua.
   */
  public static final String SKIP_PROPOSAL_PARAM = "bo_qua_cap_de_xuat";

  @Bean
  JdbcReviewRepository reviewRepository(@Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcReviewRepository(JdbcClient.create(dataSource));
  }

  @Bean
  ReviewNotices reviewNotices(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxReviewNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "appraisal",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  ProposalStep appraisalProposalStep(ObjectProvider<PolicyQueryApi> policies) {
    PolicyQueryApi api = policies.getIfAvailable();
    return (tenant, row) ->
        api != null
            && row.policyPackVersionId() != null
            && api.parameter(tenant, row.policyPackVersionId(), SKIP_PROPOSAL_PARAM)
                .map(Boolean.TRUE::equals)
                .orElse(false);
  }

  @Bean
  ReviewService reviewService(
      JdbcAppraisalProfileRepository appraisalProfileRepository,
      JdbcSelfAssessmentRepository selfAssessmentRepository,
      JdbcReviewRepository reviewRepository,
      AppraisalProfileService appraisalProfileService,
      TextSealer appraisalTextSealer,
      ObjectProvider<EvidenceQueryApi> evidenceQuery,
      ReviewNotices reviewNotices,
      ProposalStep appraisalProposalStep) {
    EvidenceQueryApi evidence = evidenceQuery.getIfAvailable();
    return new ReviewService(
        appraisalProfileRepository,
        selfAssessmentRepository,
        reviewRepository,
        appraisalProfileService,
        appraisalTextSealer,
        evidence == null ? null : new ProductEvidenceAcceptors(evidence),
        reviewNotices,
        appraisalProposalStep,
        java.time.Clock.systemUTC());
  }

  @Bean
  ReviewSubmission reviewSubmission(
      ReviewService reviewService, SignRequests appraisalSignRequests) {
    return new ReviewSubmission(reviewService, appraisalSignRequests);
  }

  @Bean
  InMemoryReviewExports appraisalReviewExports() {
    return new InMemoryReviewExports();
  }

  @Bean
  ReviewExportService reviewExportService(
      ReviewService reviewService, InMemoryReviewExports appraisalReviewExports) {
    return new ReviewExportService(
        reviewService,
        new FastExcelReviewSheets(),
        appraisalReviewExports,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  @Bean
  ReviewSignJobs reviewSignJobs(ReviewService reviewService) {
    return new ReviewSignJobs(reviewService);
  }
}
