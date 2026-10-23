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
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardSubmission;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectEligibility;
import vn.viettel.okrkpi.appraisal.application.prospect.port.ProspectCouncil;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-04-12: phân nhóm triển vọng trên {@code appraisalDataSource} và outbox {@code appraisal}.
 */
@Configuration(proxyBeanMethods = false)
public class ProspectBoardConfiguration {

  @Bean
  JdbcProspectBoardRepository prospectBoardRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcProspectBoardRepository(JdbcClient.create(dataSource));
  }

  @Bean
  ProspectBoardService prospectBoardService(
      JdbcProspectBoardRepository prospectBoardRepository,
      PeriodApi periods,
      ObjectProvider<PolicyQueryApi> policies,
      TextSealer appraisalTextSealer,
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json) {
    return new ProspectBoardService(
        prospectBoardRepository,
        periods,
        new ProspectEligibility(null, policies.getIfAvailable()),
        appraisalTextSealer,
        new OutboxProspectEvents(
            new OutboxWriter(
                new JdbcTemplate(dataSource),
                "appraisal",
                new ApicurioPayloadSerializer(registry, Map.of()),
                json.getIfAvailable(ObjectMapper::new),
                Clock.systemUtc())));
  }

  @Bean
  ProspectBoardSubmission prospectBoardSubmission(
      ProspectBoardService prospectBoardService, ObjectProvider<ProspectCouncil> council) {
    return new ProspectBoardSubmission(prospectBoardService, council.getIfAvailable());
  }
}
