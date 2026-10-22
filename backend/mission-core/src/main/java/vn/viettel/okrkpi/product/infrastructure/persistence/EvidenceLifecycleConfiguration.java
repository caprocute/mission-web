package vn.viettel.okrkpi.product.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.product.application.evidence.EvidenceLifecycleService;
import vn.viettel.okrkpi.task.api.TaskApi;

/** CORE-03-02: nối máy trạng thái minh chứng với {@code productDataSource} và {@code TaskApi}. */
@Configuration(proxyBeanMethods = false)
public class EvidenceLifecycleConfiguration {

  @Bean
  JdbcEvidenceLifecycleRepository evidenceLifecycleRepository(
      @Qualifier("productDataSource") DataSource dataSource) {
    return new JdbcEvidenceLifecycleRepository(JdbcClient.create(dataSource));
  }

  @Bean
  EvidenceLifecycleService evidenceLifecycleService(
      JdbcEvidenceLifecycleRepository evidenceLifecycleRepository,
      PeriodApi periods,
      TaskApi tasks) {
    return new EvidenceLifecycleService(
        evidenceLifecycleRepository, periods, tasks, java.time.Clock.systemUTC());
  }
}
