package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.transfer.DossierTransferService;
import vn.viettel.okrkpi.dossier.application.transfer.TransferImportService;
import vn.viettel.okrkpi.shared.audit.AuditLogger;

/**
 * DV-13-10: phần hồ sơ đã ký và kết luận kiểm điểm cho gói chuyển liên cell ở cell nguồn, và nhập
 * phần hồ sơ của gói ở cell đích.
 */
@Configuration(proxyBeanMethods = false)
public class DossierTransferConfiguration {

  @Bean
  DossierTransferService dossierTransferService(
      @Qualifier("dossierDataSource") DataSource dataSource,
      CommentSealer reviewCommentSealer,
      ObjectProvider<PeriodApi> periods,
      AuditLogger audit) {
    return new DossierTransferService(
        new JdbcDossierHistory(JdbcClient.create(dataSource)),
        reviewCommentSealer,
        periods.getIfAvailable(),
        audit);
  }

  @Bean
  TransferImportService transferImportService(
      @Qualifier("dossierDataSource") DataSource dataSource, CommentSealer reviewCommentSealer) {
    return new TransferImportService(
        new JdbcTransferredRecords(JdbcClient.create(dataSource)), reviewCommentSealer);
  }
}
