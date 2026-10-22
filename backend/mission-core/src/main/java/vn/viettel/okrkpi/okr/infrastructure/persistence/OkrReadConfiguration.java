package vn.viettel.okrkpi.okr.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.application.OkrCheckService;
import vn.viettel.okrkpi.okr.application.OkrCheckinService;
import vn.viettel.okrkpi.okr.application.OkrComposeService;
import vn.viettel.okrkpi.okr.application.OkrReadService;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinEvents;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinExtension;
import vn.viettel.okrkpi.okr.application.port.OkrSimilarityPort;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrExportJobs;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrIdempotency;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-01-02: nối phía đọc của MOD-01 với {@code okrDataSource}; giao dịch chỉ đọc chạy trên {@code
 * okrTransactionManager} để RLS nhận {@code app.tenant_id}. Ngày "hôm nay" tính số tuần của kỳ theo
 * giờ Việt Nam.
 */
@Configuration(proxyBeanMethods = false)
public class OkrReadConfiguration {

  static final ZoneId VIET_NAM = ZoneId.of("Asia/Ho_Chi_Minh");

  @Bean
  JdbcOkrReadRepository okrReadRepository(@Qualifier("okrDataSource") DataSource dataSource) {
    return new JdbcOkrReadRepository(new NamedParameterJdbcTemplate(dataSource));
  }

  @Bean
  OkrReadService okrReadService(JdbcOkrReadRepository okrReadRepository, PeriodApi periods) {
    return new OkrReadService(okrReadRepository, periods, () -> LocalDate.now(VIET_NAM));
  }

  @Bean
  JdbcOkrWriteRepository okrWriteRepository(@Qualifier("okrDataSource") DataSource dataSource) {
    return new JdbcOkrWriteRepository(new NamedParameterJdbcTemplate(dataSource));
  }

  /** CORE-01-03: phía soạn của SCR-0202 cùng {@code okrDataSource}. */
  @Bean
  OkrComposeService okrComposeService(
      JdbcOkrWriteRepository okrWriteRepository, PeriodApi periods) {
    return new OkrComposeService(okrWriteRepository, periods);
  }

  /** CORE-01-04: bộ kiểm tra BR-02-01..07 dùng chung bộ quy tắc CEL với trình duyệt. */
  @Bean
  OkrCheckService okrCheckService(
      JdbcOkrReadRepository okrReadRepository,
      JdbcOkrWriteRepository okrWriteRepository,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<OrgDirectoryApi> org,
      ObjectProvider<OkrSimilarityPort> similarity) {
    return new OkrCheckService(
        new OkrRuleChecker(),
        okrReadRepository,
        okrWriteRepository,
        policies::getIfAvailable,
        org::getIfAvailable,
        similarity::getIfAvailable);
  }

  /** CORE-01-08: check-in KR của QT-03 trên {@code okrDataSource}. */
  @Bean
  JdbcOkrCheckinRepository okrCheckinRepository(@Qualifier("okrDataSource") DataSource dataSource) {
    return new JdbcOkrCheckinRepository(new NamedParameterJdbcTemplate(dataSource));
  }

  @Bean
  OkrCheckinService okrCheckinService(
      JdbcOkrCheckinRepository okrCheckinRepository,
      PeriodApi periods,
      OkrCheckService okrCheckService,
      ObjectProvider<OkrCheckinDelegatePort> delegates,
      ObjectProvider<OkrCheckinExtension> extensions,
      OkrCheckinEvents okrCheckinEvents) {
    return new OkrCheckinService(
        okrCheckinRepository,
        periods,
        okrCheckService,
        delegates::getIfAvailable,
        () -> extensions.orderedStream().toList(),
        okrCheckinEvents);
  }

  /** CORE-01-09: EVT-02, EVT-21 của check-in ghi {@code okr.outbox_event} cùng giao dịch. */
  @Bean
  OkrCheckinEvents okrCheckinEvents(
      @Qualifier("okrDataSource") DataSource dataSource,
      @Value("${mission.okr.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<OrgDirectoryApi> org) {
    return new OutboxOkrCheckinEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "okr",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()),
        org::getIfAvailable);
  }

  @Bean
  OkrIdempotency okrIdempotency(
      @Qualifier("okrDataSource") DataSource dataSource,
      @Qualifier("okrTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new OkrIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  OkrExportJobs okrExportJobs() {
    return new OkrExportJobs();
  }
}
