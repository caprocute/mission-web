package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.transfer.RatingTransferService;
import vn.viettel.okrkpi.config.api.PeriodApi;

/** DV-13-10: phần kết quả xếp loại cho gói chuyển liên cell. */
@Configuration(proxyBeanMethods = false)
public class RatingTransferConfiguration {

  @Bean
  RatingTransferService ratingTransferService(
      @Qualifier("appraisalDataSource") DataSource dataSource, ObjectProvider<PeriodApi> periods) {
    return new RatingTransferService(
        new JdbcRatingHistory(JdbcClient.create(dataSource)), periods.getIfAvailable());
  }
}
