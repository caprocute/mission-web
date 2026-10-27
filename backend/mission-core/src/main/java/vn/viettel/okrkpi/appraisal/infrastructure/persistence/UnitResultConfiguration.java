package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.unitresult.UnitResultService;

/** AP-09: kết quả đánh giá từng cán bộ theo đơn vị cho bước đi xuống của SCR-1603. */
@Configuration(proxyBeanMethods = false)
public class UnitResultConfiguration {

  @Bean
  JdbcUnitResultRepository unitResultRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcUnitResultRepository(JdbcClient.create(dataSource));
  }

  @Bean
  UnitResultService unitResultService(JdbcUnitResultRepository unitResultRepository) {
    return new UnitResultService(unitResultRepository);
  }
}
