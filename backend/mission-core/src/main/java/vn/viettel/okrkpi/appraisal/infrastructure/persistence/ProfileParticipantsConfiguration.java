package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;

/** Cổng {@code ProfileParticipantsApi} cho MOD-07 (CORE-07-08, CORE-07-03). */
@Configuration(proxyBeanMethods = false)
public class ProfileParticipantsConfiguration {

  @Bean
  JdbcProfileParticipants profileParticipants(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<EvidenceQueryApi> evidence) {
    return new JdbcProfileParticipants(
        JdbcClient.create(dataSource), tm, evidence.getIfAvailable());
  }
}
