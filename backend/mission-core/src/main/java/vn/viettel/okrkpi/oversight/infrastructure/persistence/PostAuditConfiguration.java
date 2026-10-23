package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.postaudit.PostAuditService;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Đợt hậu kiểm (CORE-07-09): kho JDBC trên {@code oversightDataSource}, phát hiện C2 mã hoá bằng
 * {@code FieldEncryptor} như khiếu nại (không có thì đóng đợt trả 503), kiểm toán lên sổ cái qua
 * {@code oversight.outbox_event}.
 */
@Configuration(proxyBeanMethods = false)
public class PostAuditConfiguration {

  @Bean
  JdbcPostAuditRepository postAuditRepository(
      @Qualifier("oversightDataSource") DataSource dataSource) {
    return new JdbcPostAuditRepository(JdbcClient.create(dataSource));
  }

  @Bean
  PostAuditService postAuditService(
      JdbcPostAuditRepository postAuditRepository,
      PeriodApi periods,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<ComplaintSealer> sealer,
      ObjectProvider<FieldEncryptor> encryptor,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    FieldEncryptor e = encryptor.getIfAvailable();
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "oversight",
            serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc());
    return new PostAuditService(
        postAuditRepository,
        periods,
        directory.getIfAvailable(),
        sealer.getIfAvailable(() -> e == null ? null : new FieldEncryptorComplaintSealer(e)),
        new OutboxOversightAudit(outbox),
        java.time.Clock.systemUTC());
  }
}
