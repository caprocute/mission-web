package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.TaskApiService;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.lifecycle.CelGuardEvaluator;
import vn.viettel.okrkpi.task.application.lifecycle.PolicyBackedTransitionTableProvider;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskProgressService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressEvents;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents;

/**
 * CORE-02-13: nối máy trạng thái nhiệm vụ với {@code taskDataSource}, outbox {@code
 * task.outbox_event} và Policy Pack khoá {@code task_lifecycle}.
 */
@Configuration(proxyBeanMethods = false)
public class TaskLifecycleConfiguration {

  @Bean
  JdbcTaskLifecycleRepository taskLifecycleRepository(
      @Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcTaskLifecycleRepository(JdbcClient.create(dataSource));
  }

  @Bean
  TaskStatusEvents taskStatusEvents(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxTaskStatusEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  CelGuardEvaluator taskGuardEvaluator() {
    return new CelGuardEvaluator(new CelEngine());
  }

  @Bean
  PolicyBackedTransitionTableProvider taskTransitionTableProvider(
      PolicyQueryApi policies,
      CelGuardEvaluator taskGuardEvaluator,
      @Value("${mission.task.lifecycle.pin-ttl:5m}") Duration pinTtl) {
    return new PolicyBackedTransitionTableProvider(policies, taskGuardEvaluator, pinTtl);
  }

  @Bean
  TaskTransitionService taskTransitionService(
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      PolicyBackedTransitionTableProvider taskTransitionTableProvider,
      CelGuardEvaluator taskGuardEvaluator,
      PeriodApi periods,
      TaskStatusEvents taskStatusEvents,
      ObjectProvider<TaskDeliverables> deliverables,
      ApplicationEventPublisher publisher) {
    return new TaskTransitionService(
        taskLifecycleRepository,
        taskTransitionTableProvider,
        taskGuardEvaluator,
        periods,
        taskStatusEvents,
        deliverables.getIfAvailable(),
        publisher,
        java.time.Clock.systemUTC());
  }

  @Bean
  TaskApiService taskApiService(
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      TaskTransitionService taskTransitionService,
      ObjectProvider<TaskDraftService> taskDraftService,
      ObjectProvider<TaskAssignService> taskAssignService) {
    return new TaskApiService(
        taskLifecycleRepository,
        taskTransitionService,
        taskDraftService::getIfAvailable,
        taskAssignService::getIfAvailable);
  }

  @Bean
  TaskDelayRiskService taskDelayRiskService(
      @Qualifier("taskDataSource") DataSource dataSource,
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      ObjectProvider<ObjectMapper> json) {
    return new TaskDelayRiskService(
        taskLifecycleRepository,
        new JdbcTaskDelayRisks(
            JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new)));
  }

  @Bean
  TaskProgressEvents taskProgressEvents(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxTaskProgressEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  TaskProgressService taskProgressService(
      @Qualifier("taskDataSource") DataSource dataSource,
      TaskProgressEvents taskProgressEvents,
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      TaskTransitionService taskTransitionService,
      PeriodApi periods,
      @Qualifier("taskTransactionManager") PlatformTransactionManager taskTransactionManager) {
    return new TaskProgressService(
        taskLifecycleRepository,
        new JdbcTaskProgressRepository(JdbcClient.create(dataSource)),
        taskProgressEvents,
        taskTransitionService,
        periods,
        taskTransactionManager,
        java.time.Clock.systemUTC());
  }
}
