package vn.viettel.okrkpi.product.infrastructure.reconcile;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Đăng ký nguồn R2 của MOD-03 khi bật đối soát sổ cái ({@code mission.reconcile.ledger.base-url}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.reconcile.ledger", name = "base-url")
public class ProductLedgerSourceConfiguration {

  @Bean
  ProductLedgerSource productLedgerSource(
      @Qualifier("productDataSource") DataSource dataSource,
      @Qualifier("productTransactionManager") PlatformTransactionManager tm) {
    return new ProductLedgerSource(new JdbcTemplate(dataSource), tm);
  }
}
