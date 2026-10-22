package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.blocker.TaskBlockerService;
import vn.viettel.okrkpi.task.application.blocker.port.BlockerNotices;

/** CORE-02-20: điểm nghẽn trên {@code taskDataSource} và outbox {@code task.outbox_event}. */
@Configuration(proxyBeanMethods = false)
public class TaskBlockerConfiguration {

  @Bean
  JdbcTaskBlockers taskBlockers(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcTaskBlockers(JdbcClient.create(dataSource));
  }

  @Bean
  BlockerNotices blockerNotices(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxBlockerNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  TaskBlockerService taskBlockerService(
      JdbcTaskBlockers taskBlockers,
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      JdbcTaskDraftRepository taskDraftRepository,
      PeriodApi periods,
      OrgDirectoryApi org,
      BlockerNotices blockerNotices) {
    return new TaskBlockerService(
        taskBlockers,
        taskLifecycleRepository,
        taskDraftRepository,
        periods,
        org,
        blockerNotices,
        java.time.Clock.systemUTC());
  }
}
