package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-06-03: góp ý kiểm điểm trên {@code dossierDataSource}; nội dung C2 mã hoá bằng {@code
 * FieldEncryptor}, chế độ ẩn danh đọc từ {@code TenantSettingApi}.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewCommentConfiguration {

  @Bean
  JdbcReviewCommentRepository reviewCommentRepository(
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new JdbcReviewCommentRepository(JdbcClient.create(dataSource));
  }

  /** Không có {@code FieldEncryptor} thì từ chối ghi và đọc, không bao giờ lưu C2 dạng rõ. */
  @Bean
  CommentSealer reviewCommentSealer(ObjectProvider<FieldEncryptor> encryptor) {
    FieldEncryptor e = encryptor.getIfAvailable();
    if (e != null) {
      return new FieldEncryptorCommentSealer(e);
    }
    return new CommentSealer() {
      @Override
      public Sealed seal(TenantId tenant, String column, UUID rowId, String plain) {
        throw new IllegalStateException("Chưa cấu hình mã hoá trường C2");
      }

      @Override
      public String open(TenantId tenant, String column, UUID rowId, byte[] cipher) {
        throw new IllegalStateException("Chưa cấu hình mã hoá trường C2");
      }
    };
  }

  @Bean
  ReviewCommentService reviewCommentService(
      JdbcReviewMeetingRepository reviewMeetingRepository,
      JdbcReviewCommentRepository reviewCommentRepository,
      CommentSealer reviewCommentSealer,
      PeriodApi periodApi,
      ObjectProvider<TenantSettingApi> settings) {
    return new ReviewCommentService(
        reviewMeetingRepository,
        reviewCommentRepository,
        reviewCommentSealer,
        periodApi,
        settings::getIfAvailable,
        java.time.Clock.systemUTC());
  }
}
