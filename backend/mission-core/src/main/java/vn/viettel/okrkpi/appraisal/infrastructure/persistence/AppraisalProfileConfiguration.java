package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

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
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalStepEvents;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-04-03: nối máy trạng thái hồ sơ đánh giá với {@code appraisalDataSource}, outbox {@code
 * appraisal.outbox_event} và tham số Policy Pack.
 */
@Configuration(proxyBeanMethods = false)
public class AppraisalProfileConfiguration {

  @Bean
  JdbcAppraisalProfileRepository appraisalProfileRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcAppraisalProfileRepository(JdbcClient.create(dataSource));
  }

  @Bean
  AppraisalStepEvents appraisalStepEvents(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxAppraisalStepEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "appraisal",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  AppraisalProfileService appraisalProfileService(
      JdbcAppraisalProfileRepository appraisalProfileRepository,
      AppraisalStepEvents appraisalStepEvents,
      ObjectProvider<PolicyQueryApi> policies) {
    return new AppraisalProfileService(
        appraisalProfileRepository,
        appraisalStepEvents,
        policies.getIfAvailable(),
        java.time.Clock.systemUTC());
  }
}
