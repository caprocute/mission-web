package vn.viettel.okrkpi.okr.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.application.OkrCheckService;
import vn.viettel.okrkpi.okr.application.OkrCheckinDelegateService;
import vn.viettel.okrkpi.okr.application.OkrCheckinService;
import vn.viettel.okrkpi.okr.application.OkrComposeService;
import vn.viettel.okrkpi.okr.application.OkrReadService;
import vn.viettel.okrkpi.okr.application.OkrReviewService;
import vn.viettel.okrkpi.okr.application.OkrRevisionCarryOver;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker;
import vn.viettel.okrkpi.okr.application.OkrSubmitService;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalEvents;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalSignal;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinEvents;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinExtension;
import vn.viettel.okrkpi.okr.application.port.OkrSimilarityPort;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrApproveBatchJobs;
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

  /** CORE-01-05: trình phê duyệt; tiến trình duyet-okr khởi tạo sau commit qua cổng. */
  @Bean
  OkrSubmitService okrSubmitService(
      JdbcOkrWriteRepository okrWriteRepository,
      OkrReadService okrReadService,
      OkrCheckService okrCheckService,
      PeriodApi periods,
      ObjectProvider<OkrApprovalStarter> starter,
      @Qualifier("okrTransactionManager") PlatformTransactionManager tm) {
    // Chạy trong afterCommit nên phải mở giao dịch mới, không tham gia giao dịch vừa commit.
    TransactionTemplate afterCommit = new TransactionTemplate(tm);
    afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new OkrSubmitService(
        okrWriteRepository,
        okrReadService,
        okrCheckService,
        periods,
        starter::getIfAvailable,
        afterCommit);
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

  /** CORE-01-12: uỷ quyền check-in thay, đồng thời là cổng người được check-in của CORE-01-08. */
  @Bean
  JdbcOkrCheckinDelegates okrCheckinDelegates(@Qualifier("okrDataSource") DataSource dataSource) {
    return new JdbcOkrCheckinDelegates(
        new NamedParameterJdbcTemplate(dataSource), java.time.Clock.system(VIET_NAM));
  }

  @Bean
  OkrCheckinDelegateService okrCheckinDelegateService(
      JdbcOkrCheckinRepository okrCheckinRepository,
      JdbcOkrCheckinDelegates okrCheckinDelegates,
      PeriodApi periods) {
    return new OkrCheckinDelegateService(
        okrCheckinRepository, okrCheckinDelegates, periods, java.time.Clock.system(VIET_NAM));
  }

  /** CORE-01-12: chuyển giá trị KR sang phiên bản điều chỉnh vừa được duyệt. */
  @Bean
  OkrRevisionCarryOver okrRevisionCarryOver(
      JdbcOkrCheckinRepository okrCheckinRepository, JdbcOkrCheckinDelegates okrCheckinDelegates) {
    return new OkrRevisionCarryOver(okrCheckinRepository, okrCheckinDelegates);
  }

  /** CORE-01-06: duyệt, trả lại, rút lại mục tiêu; EVT-01, EVT-21 ghi outbox cùng giao dịch. */
  @Bean
  JdbcOkrReviewRepository okrReviewRepository(@Qualifier("okrDataSource") DataSource dataSource) {
    return new JdbcOkrReviewRepository(new NamedParameterJdbcTemplate(dataSource));
  }

  @Bean
  OkrApprovalEvents okrApprovalEvents(
      @Qualifier("okrDataSource") DataSource dataSource,
      @Value("${mission.okr.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<OrgDirectoryApi> org) {
    return new OutboxOkrApprovalEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "okr",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()),
        org::getIfAvailable);
  }

  @Bean
  OkrReviewService okrReviewService(
      JdbcOkrReviewRepository okrReviewRepository,
      OkrReadService okrReadService,
      OkrCheckService okrCheckService,
      PeriodApi periods,
      OkrApprovalEvents okrApprovalEvents,
      ObjectProvider<OkrApprovalSignal> signal,
      ApplicationEventPublisher publisher) {
    return new OkrReviewService(
        okrReviewRepository,
        okrReadService,
        okrCheckService,
        periods,
        okrApprovalEvents,
        signal::getIfAvailable,
        publisher::publishEvent);
  }

  /** Lô duyệt chạy nền trên luồng ảo, mỗi lô một luồng, mỗi bộ một giao dịch. */
  @Bean(destroyMethod = "close")
  ExecutorService okrApproveBatchExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("okr-duyet-lo-", 0).factory());
  }

  @Bean
  OkrApproveBatchJobs okrApproveBatchJobs(
      OkrReviewService okrReviewService,
      @Qualifier("okrApproveBatchExecutor") ExecutorService okrApproveBatchExecutor) {
    return new OkrApproveBatchJobs(okrReviewService, okrApproveBatchExecutor);
  }
}
