package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Cổng {@code ProfileParticipantsApi} cho MOD-07 (CORE-07-08). */
@Configuration(proxyBeanMethods = false)
public class ProfileParticipantsConfiguration {

  @Bean
  JdbcProfileParticipants profileParticipants(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm) {
    return new JdbcProfileParticipants(JdbcClient.create(dataSource), tm);
  }
}
