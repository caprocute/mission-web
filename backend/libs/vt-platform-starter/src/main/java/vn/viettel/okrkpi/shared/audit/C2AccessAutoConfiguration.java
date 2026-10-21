package vn.viettel.okrkpi.shared.audit;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.shared.crypto.CryptoAutoConfiguration;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.outbox.OutboxAutoConfiguration;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-00-05 · Bật nhật ký truy cập C2 khi service có cả {@link FieldEncryptor} và {@link
 * OutboxWriter}: gắn {@link C2AccessAuditor} làm điểm móc giải mã và đăng ký advisor cho {@link
 * C2Access}. Tắt bằng {@code okrkpi.audit.c2.enabled=false} (chỉ dùng trong kiểm thử).
 */
@AutoConfiguration(after = {CryptoAutoConfiguration.class, OutboxAutoConfiguration.class})
@ConditionalOnProperty(
    prefix = "okrkpi.audit.c2",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnBean({FieldEncryptor.class, OutboxWriter.class})
public class C2AccessAutoConfiguration {

  /** Số đối tượng tối đa trong một sự kiện gom; vượt thì chia lô (suy luận). */
  static final int DEFAULT_MAX_TARGETS = 100;

  @Bean
  @ConditionalOnMissingBean
  public C2AccessAuditor c2AccessAuditor(
      OutboxWriter outbox,
      ObjectProvider<PlatformTransactionManager> transactionManager,
      ObjectProvider<C2ActorResolver> actors,
      FieldEncryptor encryptor,
      Environment env) {
    C2AccessAuditor auditor =
        new C2AccessAuditor(
            outbox,
            transactionManager.getObject(),
            actors.getIfAvailable(() -> () -> C2Actor.SYSTEM),
            env.getProperty(
                "okrkpi.audit.c2.max-targets-per-event", Integer.class, DEFAULT_MAX_TARGETS));
    encryptor.setDecryptListener(auditor);
    return auditor;
  }

  @Bean
  @ConditionalOnMissingBean(name = "c2AccessAdvisor")
  public Advisor c2AccessAdvisor(C2AccessAuditor auditor) {
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, C2Access.class, true),
            new C2AccessInterceptor(auditor));
    advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
    return advisor;
  }

  /** Người xem lấy từ JWT khi có Spring Security OAuth2; không có thì coi là tác vụ hệ thống. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.Jwt")
  static class JwtActorConfiguration {

    @Bean
    @ConditionalOnMissingBean
    C2ActorResolver jwtC2ActorResolver() {
      return new JwtC2ActorResolver();
    }
  }
}
