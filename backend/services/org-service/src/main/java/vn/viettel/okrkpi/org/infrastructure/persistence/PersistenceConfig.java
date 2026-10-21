package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Trình quản lý giao dịch đặt ngữ cảnh tenant; đồng hồ theo múi giờ nghiệp vụ. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new TenantAwareTransactionManager(dataSource);
  }

  @Bean
  Clock clock(@Value("${okrkpi.org.zone:Asia/Ho_Chi_Minh}") String zone) {
    return Clock.system(ZoneId.of(zone));
  }
}
