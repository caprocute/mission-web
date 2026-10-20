package vn.viettel.okrkpi.shared.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import vn.viettel.okrkpi.shared.kernel.Clock;

/** Auto-configuration nhật ký kiểm toán (TL-01 "Tách nhật ký kiểm toán"). */
@AutoConfiguration
public class AuditAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AuditAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public Clock platformClock() {
    return Clock.systemUtc();
  }

  @Bean
  @ConditionalOnMissingBean
  public AuditSink auditSink() {
    log.warn(
        "Chưa có AuditSink ghi outbox ({}): sự kiện kiểm toán tạm ghi kênh nhật ký kiểm toán riêng",
        AuditSink.TOPIC);
    return new LoggingAuditSink();
  }

  @Bean
  @ConditionalOnMissingBean
  public AuditLogger auditLogger(AuditSink auditSink, Clock platformClock) {
    return new DefaultAuditLogger(auditSink, platformClock);
  }
}
