package vn.viettel.okrkpi.shared.site;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;

/** INF-28 · QA-15 · Chế độ chờ của ứng dụng ở TTDL không giữ primary. */
@AutoConfiguration
@EnableConfigurationProperties(SiteProperties.class)
public class SiteAutoConfiguration {

  @Bean(name = "siteHealthIndicator")
  @ConditionalOnMissingBean(name = "siteHealthIndicator")
  @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
  public SiteHealthIndicator siteHealthIndicator(SiteProperties properties) {
    return new SiteHealthIndicator(properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = "okrkpi.site", name = "role", havingValue = "standby")
  public StandbySchedulingConfigurer standbySchedulingConfigurer(
      SiteProperties properties, ObjectProvider<TaskScheduler> schedulers) {
    return new StandbySchedulingConfigurer(properties, schedulers);
  }
}
