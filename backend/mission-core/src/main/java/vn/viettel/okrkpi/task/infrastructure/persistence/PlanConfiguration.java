package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.task.application.catalog.CatalogPolicy;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;
import vn.viettel.okrkpi.task.application.plan.PlanService;

/** CORE-02-05: kế hoạch công tác quý trên {@code taskDataSource}. */
@Configuration(proxyBeanMethods = false)
public class PlanConfiguration {

  @Bean
  JdbcPlanRepository planRepository(
      @Qualifier("taskDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcPlanRepository(dataSource, json.getIfAvailable(ObjectMapper::new));
  }

  /** CORE-02-06: BR-04-02..07; BR-04-03, BR-04-04 biên dịch CEL một lần khi khởi động. */
  @Bean
  PlanChecker planChecker() {
    return new PlanChecker(new CelEngine());
  }

  @Bean
  PlanService planService(
      JdbcPlanRepository planRepository,
      PeriodApi periods,
      OkrApi okr,
      ObjectProvider<OrgDirectoryApi> org,
      PlanChecker planChecker,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<CatalogPolicy> catalogPolicy) {
    return new PlanService(
        planRepository,
        periods,
        okr,
        org.getIfAvailable(),
        planChecker,
        policies.getIfAvailable(),
        catalogPolicy.getIfAvailable(),
        Clock.systemDefaultZone());
  }
}
