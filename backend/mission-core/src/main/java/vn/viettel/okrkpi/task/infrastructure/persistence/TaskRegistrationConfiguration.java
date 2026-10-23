package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.TaskRegistrationService;
import vn.viettel.okrkpi.task.application.assign.port.RegistrationNotices;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;

/** CORE-02-17: cán bộ đăng ký nhiệm vụ, người quản lý duyệt hoặc từ chối. */
@Configuration(proxyBeanMethods = false)
public class TaskRegistrationConfiguration {

  @Bean
  RegistrationNotices registrationNotices(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxRegistrationNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, java.util.Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc()));
  }

  @Bean
  TaskRegistrationService taskRegistrationService(
      @Qualifier("taskDataSource") DataSource dataSource,
      TaskDraftService taskDraftService,
      JdbcTaskDraftRepository taskDraftRepository,
      TaskAssignService taskAssignService,
      TaskTransitionService taskTransitionService,
      CatalogService catalogService,
      OrgDirectoryApi org,
      PeriodApi periods,
      RegistrationNotices registrationNotices) {
    return new TaskRegistrationService(
        taskDraftService,
        taskDraftRepository,
        taskAssignService,
        taskTransitionService,
        catalogService,
        org,
        periods,
        new JdbcTaskRegistrations(JdbcClient.create(dataSource)),
        registrationNotices,
        java.time.Clock.systemUTC());
  }
}
