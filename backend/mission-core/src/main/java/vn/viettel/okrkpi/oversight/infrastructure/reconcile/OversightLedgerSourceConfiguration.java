package vn.viettel.okrkpi.oversight.infrastructure.reconcile;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Đăng ký nguồn R2 của MOD-07 khi bật đối soát sổ cái ({@code mission.reconcile.ledger.base-url}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.reconcile.ledger", name = "base-url")
public class OversightLedgerSourceConfiguration {

  @Bean
  OversightLedgerSource oversightLedgerSource(
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Qualifier("oversightTransactionManager") PlatformTransactionManager tm) {
    return new OversightLedgerSource(new JdbcTemplate(dataSource), tm);
  }
}
