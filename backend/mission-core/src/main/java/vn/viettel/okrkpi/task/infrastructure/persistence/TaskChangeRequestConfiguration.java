package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.change.TaskChangeDecisionService;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService;
import vn.viettel.okrkpi.task.application.change.port.ChangeApprovalStarter;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents;

/**
 * CORE-02-21: đề nghị gia hạn, điều chỉnh trên {@code taskDataSource}. Tham số BR-06-01, BR-06-06
 * đọc từ cấu hình cho tới khi Policy Pack (CORE-05-03) công bố.
 */
@Configuration(proxyBeanMethods = false)
public class TaskChangeRequestConfiguration {

  @Bean
  JdbcTaskChangeRequests taskChangeRequests(
      @Qualifier("taskDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcTaskChangeRequests(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  ChangeRequestNotices changeRequestNotices(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxChangeRequestNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  TaskChangeRequestService taskChangeRequestService(
      JdbcTaskChangeRequests taskChangeRequests,
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      JdbcTaskDraftRepository taskDraftRepository,
      JdbcTaskBlockers taskBlockers,
      PeriodApi periods,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<TaskDeliverables> deliverables,
      ChangeRequestNotices changeRequestNotices,
      ObjectProvider<ChangeApprovalStarter> starter,
      @Value("${mission.task.change.coefficient-step:0.05}") BigDecimal step,
      @Value("${mission.task.change.max-extensions-per-quarter:2}") int maxExtensions,
      @Value("${mission.task.change.max-extra-days:10}") int maxExtraDays) {
    return new TaskChangeRequestService(
        taskChangeRequests,
        taskLifecycleRepository,
        taskDraftRepository,
        taskBlockers,
        periods,
        calendar.getIfAvailable(),
        deliverables.getIfAvailable(),
        changeRequestNotices,
        new TaskChangeRequestService.Settings(step, maxExtensions, maxExtraDays),
        java.time.Clock.systemUTC(),
        starter.getIfAvailable());
  }

  @Bean
  TaskChangeDecisionService taskChangeDecisionService(
      JdbcTaskChangeRequests taskChangeRequests,
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      TaskTransitionService taskTransitionService,
      TaskStatusEvents taskStatusEvents,
      PeriodApi periods,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<TaskDeliverables> deliverables,
      ChangeRequestNotices changeRequestNotices) {
    return new TaskChangeDecisionService(
        taskChangeRequests,
        taskLifecycleRepository,
        taskTransitionService,
        taskStatusEvents,
        periods,
        calendar.getIfAvailable(),
        deliverables.getIfAvailable(),
        changeRequestNotices,
        java.time.Clock.systemUTC());
  }
}
