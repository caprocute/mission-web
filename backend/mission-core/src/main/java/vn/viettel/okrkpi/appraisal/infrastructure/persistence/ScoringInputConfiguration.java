package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.scoring.IncidentService;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.task.api.TaskApi;

/**
 * CORE-04-06: đầu vào tính điểm cho SVC-07 trên {@code appraisalDataSource}; CORE-04-13: trường mở
 * rộng BS-03 và bảng sự việc.
 */
@Configuration(proxyBeanMethods = false)
public class ScoringInputConfiguration {

  @Bean
  JdbcScoringInputRepository scoringInputRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcScoringInputRepository(JdbcClient.create(dataSource));
  }

  @Bean
  ScoringInputService scoringInputService(JdbcScoringInputRepository scoringInputRepository) {
    return new ScoringInputService(scoringInputRepository, Clock.systemUtc());
  }

  @Bean
  JdbcIncidentRepository incidentRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcIncidentRepository(JdbcClient.create(dataSource));
  }

  @Bean
  IncidentService incidentService(
      JdbcIncidentRepository incidentRepository,
      TextSealer appraisalTextSealer,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<PolicyQueryApi> policies) {
    return new IncidentService(
        incidentRepository,
        appraisalTextSealer,
        periods.getIfAvailable(),
        policies.getIfAvailable(),
        Clock.systemUtc());
  }

  @Bean
  ScoringExtensionService scoringExtensionService(
      JdbcScoringInputRepository scoringInputRepository,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<TaskApi> tasks,
      ObjectProvider<OkrApi> okr,
      IncidentService incidentService) {
    return new ScoringExtensionService(
        scoringInputRepository,
        policies.getIfAvailable(),
        tasks.getIfAvailable(),
        okr.getIfAvailable(),
        incidentService);
  }
}
