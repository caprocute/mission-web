package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringInputService;
import vn.viettel.okrkpi.shared.kernel.Clock;

/** CORE-04-06: đầu vào tính điểm cho SVC-07 trên {@code appraisalDataSource}. */
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
}
