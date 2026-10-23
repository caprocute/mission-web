package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;

/** Cổng {@code ComplaintProfilesApi} cho MOD-07 (CORE-07-02). */
@Configuration(proxyBeanMethods = false)
public class ComplaintProfilesConfiguration {

  @Bean
  JdbcComplaintProfiles complaintProfiles(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
      AppraisalProfileService appraisalProfileService,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<ObjectMapper> json) {
    return new JdbcComplaintProfiles(
        JdbcClient.create(dataSource),
        tm,
        appraisalProfileService,
        policies.getIfAvailable(),
        json.getIfAvailable(ObjectMapper::new));
  }

  /** Cổng {@code DossierProfilesApi} cho gói hồ sơ số của MOD-06 (CORE-06-06). */
  @Bean
  JdbcDossierProfiles dossierProfiles(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<PolicyQueryApi> policies) {
    return new JdbcDossierProfiles(JdbcClient.create(dataSource), tm, policies.getIfAvailable());
  }
}
