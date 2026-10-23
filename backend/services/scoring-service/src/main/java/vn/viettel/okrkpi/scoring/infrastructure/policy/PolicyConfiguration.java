package vn.viettel.okrkpi.scoring.infrastructure.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPublishedHandler;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.application.port.OpenPeriods;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Lắp ráp nạp Policy Pack, biên dịch CEL và DMN, đệm theo băm và consumer EVT-13 (DV-07-02). */
@Configuration(proxyBeanMethods = false)
public class PolicyConfiguration {

  @Bean
  CelEngine scoringCelEngine() {
    return new CelEngine(StaticWorkingCalendar.weekendsOnly());
  }

  @Bean
  FlowableDecisionTables decisionTables() {
    return new FlowableDecisionTables();
  }

  @Bean
  PolicyCompiler policyCompiler(CelEngine scoringCelEngine, DecisionTables decisionTables) {
    return new PolicyCompiler(scoringCelEngine, decisionTables);
  }

  @Bean
  PolicyPackSource policyPackSource(
      ResilientClientFactory clients,
      @Value("${mission.scoring.policy.base-url:http://127.0.0.1:8080}") String baseUrl) {
    return new HttpPolicyPackSource(clients.create("policy-pack", baseUrl));
  }

  @Bean
  PolicyPackCache policyPackCache(
      PolicyPackSource source,
      PolicyCompiler compiler,
      @Value("${mission.scoring.policy.cache-max-entries:32}") int maxEntries) {
    return new PolicyPackCache(source, compiler, maxEntries);
  }

  @Bean
  OpenPeriods openPeriods(JdbcClient jdbc) {
    return new JdbcOpenPeriods(jdbc);
  }

  @Bean
  PolicyPublishedHandler policyPublishedHandler(
      PolicyPackCache cache, OpenPeriods periods, ScoreJobStore jobs) {
    return new PolicyPublishedHandler(cache, periods, jobs);
  }

  @Bean
  PolicyPackPublishedConsumer policyPackPublishedConsumer(PolicyPublishedHandler handler) {
    return new PolicyPackPublishedConsumer(handler);
  }
}
