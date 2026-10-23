package vn.viettel.okrkpi.workflow.infrastructure.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.workflow.application.port.CellEngineGateway;
import vn.viettel.okrkpi.workflow.application.port.RolloutCalendar;
import vn.viettel.okrkpi.workflow.application.port.TemplateAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.TemplateBindingUsage;
import vn.viettel.okrkpi.workflow.application.port.TemplateBpmnLinter;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;
import vn.viettel.okrkpi.workflow.application.port.TemplateEventPublisher;
import vn.viettel.okrkpi.workflow.application.port.TemplateReleaseStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateRolloutStore;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestDispatcher;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestEngine;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestRunStore;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.application.template.ReleaseSettings;
import vn.viettel.okrkpi.workflow.application.template.RolloutProgress;
import vn.viettel.okrkpi.workflow.application.template.TemplateReleaseSaga;
import vn.viettel.okrkpi.workflow.application.template.TemplateTestService;
import vn.viettel.okrkpi.workflow.application.template.WorkflowTemplateService;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcTemplateBindingUsage;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcTemplateReleaseStore;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcTemplateRolloutStore;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcTemplateTestRunStore;
import vn.viettel.okrkpi.workflow.infrastructure.persistence.JdbcWorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.infrastructure.template.AuthoredTemplateLinter;

/**
 * Lắp thư viện và vòng đời mẫu luồng nội bộ SCR-1505 (BS-01, DV-06-09): kho JDBC, linter máy chủ,
 * engine thử, các cổng ra hệ thống ngoài với bộ chuyển mặc định chạy được ở dev/test, saga phát
 * hành trên khung DV-06-05.
 */
@Configuration(proxyBeanMethods = false)
public class TemplateReleaseConfiguration {

  static final String PREFIX = "mission.workflow.release";

  @Bean
  ReleaseSettings templateReleaseSettings(
      @Value("${" + PREFIX + ".cells:}") String cells,
      @Value("${" + PREFIX + ".signature-timeout:PT30M}") Duration signatureTimeout,
      @Value("${" + PREFIX + ".sql-budget:20}") int sqlBudget,
      @Value("${" + PREFIX + ".cell-retry.max-attempts:3}") int attempts,
      @Value("${" + PREFIX + ".cell-retry.initial-backoff:PT30S}") Duration backoff,
      @Value("${" + PREFIX + ".cell-retry.max-backoff:PT5M}") Duration maxBackoff) {
    return new ReleaseSettings(
        split(cells),
        signatureTimeout,
        new RetryPolicy(
            attempts, backoff, 2.0, maxBackoff.compareTo(backoff) < 0 ? backoff : maxBackoff),
        sqlBudget);
  }

  @Bean
  WorkflowTemplateStore workflowTemplateStore(JdbcTemplate jdbc, ObjectMapper json) {
    return new JdbcWorkflowTemplateStore(jdbc, json);
  }

  @Bean
  TemplateTestRunStore templateTestRunStore(
      JdbcTemplate jdbc, ObjectMapper json, ReleaseSettings settings) {
    return new JdbcTemplateTestRunStore(jdbc, json, settings.sqlBudget());
  }

  @Bean
  TemplateReleaseStore templateReleaseStore(JdbcTemplate jdbc) {
    return new JdbcTemplateReleaseStore(jdbc);
  }

  @Bean
  TemplateRolloutStore templateRolloutStore(JdbcTemplate jdbc) {
    return new JdbcTemplateRolloutStore(jdbc);
  }

  @Bean
  TemplateBindingUsage templateBindingUsage(JdbcTemplate jdbc, ObjectMapper json) {
    return new JdbcTemplateBindingUsage(jdbc, json);
  }

  @Bean
  TemplateBpmnLinter templateBpmnLinter() {
    return new AuthoredTemplateLinter();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = PREFIX + ".code-host",
      name = "type",
      havingValue = "mailbox",
      matchIfMissing = true)
  TemplateCodeHost mailboxTemplateCodeHost(
      JdbcTemplate jdbc,
      ObjectMapper json,
      @Value("${" + PREFIX + ".code-host.merge-request-base-url:}") String baseUrl) {
    return new MailboxTemplateCodeHost(jdbc, json, baseUrl);
  }

  @Bean
  @ConditionalOnProperty(prefix = PREFIX + ".code-host", name = "type", havingValue = "gitlab")
  TemplateCodeHost gitLabTemplateCodeHost(
      RestClient.Builder builder,
      @Value("${" + PREFIX + ".code-host.api-base-url}") String apiBaseUrl,
      @Value("${" + PREFIX + ".code-host.project}") String project,
      @Value("${" + PREFIX + ".code-host.target-branch:main}") String targetBranch,
      @Value("${" + PREFIX + ".code-host.token-file:}") String tokenFile) {
    return new GitLabTemplateCodeHost(builder, apiBaseUrl, project, targetBranch, tokenFile);
  }

  @Bean
  @ConditionalOnMissingBean(CellEngineGateway.class)
  LocalCellEngineGateway localCellEngineGateway(
      RepositoryService repository,
      ManagementService management,
      @Value("${" + PREFIX + ".local-cell.outage-cells:}") String outages) {
    return new LocalCellEngineGateway(repository, management, split(outages));
  }

  @Bean
  @ConditionalOnMissingBean(RolloutCalendar.class)
  RolloutCalendar rolloutCalendar(
      @Value("${" + PREFIX + ".window.zone:Asia/Ho_Chi_Minh}") String zone,
      @Value("${" + PREFIX + ".window.peak-hours:}") String peakHours,
      @Value("${" + PREFIX + ".window.peak-on-weekends:false}") boolean weekends,
      @Value("${" + PREFIX + ".window.period-ends:}") String periodEnds,
      @Value("${" + PREFIX + ".window.freeze-days:3}") int freezeDays) {
    return new ConfiguredRolloutCalendar(
        zone, split(peakHours), weekends, split(periodEnds), freezeDays);
  }

  @Bean
  TemplateEventPublisher templateEventPublisher(OutboxWriter outbox) {
    return new OutboxTemplateEventPublisher(outbox);
  }

  @Bean(destroyMethod = "close")
  ExecutorTemplateTestDispatcher templateTestDispatcher(
      @Value("${" + PREFIX + ".test.queue-capacity:8}") int queue) {
    return new ExecutorTemplateTestDispatcher(queue);
  }

  @Bean
  RolloutProgress rolloutProgress(TemplateRolloutStore rollouts) {
    return new RolloutProgress(rollouts);
  }

  @Bean
  WorkflowTemplateService workflowTemplateService(
      WorkflowTemplateStore store,
      TemplateBpmnLinter linter,
      TemplateBindingUsage usage,
      TemplateTestRunStore runs,
      TemplateReleaseStore releases,
      TemplateRolloutStore rollouts,
      TemplateCodeHost codeHost,
      TemplateAccessPolicy access,
      TemplateTestEngine engine,
      RolloutCalendar calendar,
      ObjectProvider<SagaService> sagas,
      ReleaseSettings settings,
      ObjectProvider<Clock> clock) {
    return new WorkflowTemplateService(
        new WorkflowTemplateService.Ports(
            store, linter, usage, runs, releases, rollouts, codeHost, access, engine, calendar),
        sagas.getIfAvailable(),
        settings,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  TemplateTestService templateTestService(
      WorkflowTemplateStore store,
      TemplateTestRunStore runs,
      TemplateBindingUsage usage,
      TemplateTestEngine engine,
      TemplateAccessPolicy access,
      TemplateTestDispatcher dispatcher,
      PlatformTransactionManager transactions,
      ReleaseSettings settings,
      ObjectProvider<Clock> clock) {
    return new TemplateTestService(
        store,
        runs,
        usage,
        engine,
        access,
        dispatcher,
        new TransactionTemplate(transactions),
        settings.sqlBudget(),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  TemplateTestJobSource templateTestJobSource(TemplateTestService tests) {
    return new TemplateTestJobSource(tests);
  }

  /** Saga phát hành chỉ có khi khung saga bật (như {@code SagaConfiguration}). */
  @Bean
  @ConditionalOnProperty(prefix = "okrkpi.saga", name = "enabled", matchIfMissing = true)
  TemplateReleaseSaga templateReleaseSaga(
      WorkflowTemplateStore store,
      TemplateReleaseStore releases,
      TemplateRolloutStore rollouts,
      TemplateCodeHost codeHost,
      CellEngineGateway cells,
      RolloutCalendar calendar,
      TemplateEventPublisher events,
      RolloutProgress progress,
      ReleaseSettings settings,
      ObjectProvider<Clock> clock) {
    return new TemplateReleaseSaga(
        new TemplateReleaseSaga.Dependencies(
            store,
            releases,
            rollouts,
            codeHost,
            cells,
            calendar,
            events,
            progress,
            clock.getIfAvailable(Clock::systemUTC)),
        settings);
  }

  static List<String> split(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    return Arrays.stream(text.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }
}
