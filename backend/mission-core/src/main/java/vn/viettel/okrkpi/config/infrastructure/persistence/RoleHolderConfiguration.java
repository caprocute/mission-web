package vn.viettel.okrkpi.config.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Cổng {@code RoleHolderApi} cho MOD-07 chọn người duyệt mở lại kỳ (CORE-07-08). */
@Configuration(proxyBeanMethods = false)
public class RoleHolderConfiguration {

  @Bean
  JdbcUnitRoleHolders unitRoleHolders(
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm) {
    return new JdbcUnitRoleHolders(JdbcClient.create(dataSource), tm);
  }
}
