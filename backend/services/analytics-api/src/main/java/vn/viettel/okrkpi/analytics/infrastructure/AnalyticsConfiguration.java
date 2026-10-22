package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.analytics.application.CubePort;
import vn.viettel.okrkpi.analytics.application.DashboardService;
import vn.viettel.okrkpi.analytics.application.DashboardStorePort;
import vn.viettel.okrkpi.analytics.application.OrgScopePort;
import vn.viettel.okrkpi.analytics.application.QueryService;
import vn.viettel.okrkpi.analytics.application.ResultCache;
import vn.viettel.okrkpi.analytics.application.ScopeService;
import vn.viettel.okrkpi.analytics.application.SemanticModelService;
import vn.viettel.okrkpi.analytics.application.ShareNotifier;
import vn.viettel.okrkpi.shared.cache.TenantCache;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Nối các thành phần của analytics-api. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {

  @Bean
  Clock analyticsClock() {
    return Clock.systemUTC();
  }

  @Bean
  HttpClient analyticsHttpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @Bean
  CubePort cubePort(
      AnalyticsProperties p,
      ResilientClientFactory clients,
      ObjectProvider<RestClient.Builder> builders,
      ObjectMapper json,
      Clock clock) {
    RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
    return new CubeRestClient(
        clients.create("cube", builder.baseUrl(p.cubeUrl()).build()),
        new CubeTokens(p.cubeApiSecret(), clock),
        json,
        p.cubeTimeout(),
        clock);
  }

  @Bean
  OrgScopePort orgScopePort(AnalyticsProperties p, HttpClient http, ObjectMapper json) {
    return new ClickHouseOrgScope(
        http,
        p.clickhouseUrl(),
        p.clickhouseDatabase(),
        p.clickhouseUsername(),
        p.clickhousePassword(),
        json,
        Duration.ofSeconds(10));
  }

  @Bean
  ResultCache resultCache(TenantCache cache, ObjectMapper json) {
    return new ValkeyResultCache(cache, json);
  }

  @Bean
  DashboardStorePort dashboardStore(
      AnalyticsProperties p,
      ResilientClientFactory clients,
      ObjectProvider<RestClient.Builder> builders,
      ObjectMapper json,
      Clock clock) {
    RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
    ServiceToken token =
        new ServiceToken(builder.build(), p.tokenUrl(), p.clientId(), p.clientSecret(), clock);
    ObjectMapper lenient = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return new ConfigDashboardClient(
        clients.create("mission-core-config", builder.clone().baseUrl(p.coreUrl()).build()),
        token,
        lenient);
  }

  @Bean
  ScopeService scopeService(OrgScopePort org) {
    return new ScopeService(org);
  }

  @Bean
  SemanticModelService semanticModelService(CubePort cube, AnalyticsProperties p, Clock clock) {
    return new SemanticModelService(cube, p.modelRefresh(), clock);
  }

  @Bean
  QueryService queryService(
      ScopeService scopes,
      SemanticModelService models,
      CubePort cube,
      OrgScopePort org,
      ResultCache cache,
      AnalyticsProperties p,
      Clock clock) {
    return new QueryService(scopes, models, cube, org, cache, p.cacheTtl(), p.staleAfter(), clock);
  }

  @Bean
  DashboardService dashboardService(
      DashboardStorePort store,
      ScopeService scopes,
      SemanticModelService models,
      QueryService queries,
      ObjectProvider<ShareNotifier> notifiers) {
    return new DashboardService(
        store, scopes, models, queries, notifiers.getIfAvailable(() -> ShareNotifier.NONE));
  }
}
