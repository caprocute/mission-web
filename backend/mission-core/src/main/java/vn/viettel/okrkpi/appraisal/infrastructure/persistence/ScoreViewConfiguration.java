package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService;

/** CORE-04-09: đọc điểm theo luồng trên {@code appraisalDataSource}. */
@Configuration(proxyBeanMethods = false)
public class ScoreViewConfiguration {

  @Bean
  JdbcScoreSnapshotReader scoreSnapshotReader(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcScoreSnapshotReader(JdbcClient.create(dataSource));
  }

  @Bean
  ScoreViewService scoreViewService(
      JdbcSelfAssessmentRepository selfAssessmentRepository,
      JdbcScoreSnapshotReader scoreSnapshotReader) {
    return new ScoreViewService(selfAssessmentRepository, scoreSnapshotReader);
  }
}
