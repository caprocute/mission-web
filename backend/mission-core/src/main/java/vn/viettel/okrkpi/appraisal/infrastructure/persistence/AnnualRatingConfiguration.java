package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualSetupService;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRecomputeRequests;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;

/** CORE-04-10: hồ sơ năm, bảng xếp loại năm và giải thích liên thông. */
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
      ObjectProvider<AppraisalSpecialCases> specialCases) {
    return new AnnualSetupService(
        annualRatingRepository,
        periods.getIfAvailable(),
        policies.getIfAvailable(),
        specialCases.getIfAvailable());
  }

  @Bean
  AnnualRatingService annualRatingService(
      JdbcAnnualRatingRepository annualRatingRepository,
      JdbcScoreSnapshotReader scoreSnapshotReader) {
    return new AnnualRatingService(annualRatingRepository, scoreSnapshotReader);
  }
}
