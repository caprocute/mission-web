package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Cổng biểu mẫu hồ sơ số cho MOD-07 ({@code appraisal :: api}, CORE-07-09). */
@Configuration(proxyBeanMethods = false)
public class ProfileFormsConfiguration {

  @Bean
  JdbcProfileForms profileForms(
      @Qualifier("dossierDataSource") DataSource dataSource,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm) {
    return new JdbcProfileForms(dataSource, tm);
  }
}
